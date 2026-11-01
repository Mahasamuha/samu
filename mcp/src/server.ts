import type { IncomingMessage, ServerResponse } from "node:http";
import { McpServer } from "@modelcontextprotocol/sdk/server/mcp.js";
import { StreamableHTTPServerTransport } from "@modelcontextprotocol/sdk/server/streamableHttp.js";
import { z } from "zod";
import { search } from "./brave.js";
import { buildIndex, type CapabilityCatalog } from "./capabilities.js";
import type { Config } from "./config.js";
import { musicFind } from "./plex.js";
import { weather } from "./weather.js";

export function log(event: string, fields: Record<string, unknown> = {}): void {
  console.log(JSON.stringify({ time: new Date().toISOString(), event, ...fields }));
}

/** The server's tools and HTTP handler for a given config and catalog. Split from index.ts so tests can build it without listening. */
export function createHandlers(config: Config, catalog: CapabilityCatalog): {
  buildServer: () => McpServer;
  handle: (req: IncomingMessage, res: ServerResponse) => Promise<void>;
} {
  /** The tools this server offers (plan §4b). Built per request: the server keeps no state between requests. */
  function buildServer(): McpServer {
    const server = new McpServer({ name: "assistant-tools", version: config.version });

    server.registerTool(
      "web_search",
      {
        title: "Web search",
        description:
          "Search the web for anything current or changing: news, scores, prices, market closes, schedules, opening hours. " +
          "Returns the top pages with title, URL, date, and extracted text. Include the place in the query when it matters.",
        inputSchema: { query: z.string().min(1).describe("What to search for, as a search engine query.") },
        annotations: { readOnlyHint: true, openWorldHint: true },
      },
      async ({ query }) => {
        const started = performance.now();
        const results = await search(config, query);
        log("web_search", { results: results.length, chars: results.reduce((n, r) => n + r.text.length, 0), ms: Math.round(performance.now() - started) });
        return { content: [{ type: "text", text: JSON.stringify({ results }) }] };
      },
    );

    if (config.pirateWeatherApiKey) {
      server.registerTool(
        "get_weather",
        {
          title: "Weather",
          description:
            "Live weather, including official alerts and rain in the next hour, for a place. Omit day for current conditions " +
            "and the rest of today and tonight. Pass day for one date: its high, low, and hour-by-hour forecast. Pass day and " +
            "end_day for a range, such as a weekend: a summary per day. Forecasts reach 7 days ahead. Use this for every " +
            "weather question instead of web search.",
          inputSchema: {
            place: z.string().min(1).describe("A city or place name. When the user doesn't name one, use the user's location from the context."),
            day: z.string().optional().describe("The date asked about, as YYYY-MM-DD, worked out from the current date. Omit it for now, today, or tonight."),
            end_day: z.string().optional().describe("The last date of a range, as YYYY-MM-DD, with day as the first."),
          },
          annotations: { readOnlyHint: true, openWorldHint: true },
        },
        async ({ place, day, end_day }) => {
          const started = performance.now();
          const result = await weather(config, { place, day, endDay: end_day });
          log("get_weather", { alerts: Array.isArray(result.alerts) ? result.alerts.length : 0, ms: Math.round(performance.now() - started) });
          return { content: [{ type: "text", text: JSON.stringify(result) }] };
        },
      );
    }

    if (config.plexUrl && config.plexToken) {
      server.registerTool(
        "music_find",
        {
          title: "Find music",
          description:
            "Look up what the user asked to play in their own music library, and return the exact names the library uses. " +
            "Not found returns close suggestions.",
          inputSchema: {
            kind: z.enum(["album", "song", "artist", "playlist", "genre"]).describe("What kind of thing the name is."),
            name: z.string().min(1).describe("The name as the user said it."),
            artist: z.string().optional().describe("The artist, when the user named one with an album or song."),
          },
          annotations: { readOnlyHint: true, openWorldHint: false },
        },
        async ({ kind, name, artist }) => {
          const started = performance.now();
          const result = await musicFind(config, { kind, name, artist });
          // Names and queries are the user's listening, so only the outcome is logged.
          log("music_find", { kind, status: result.status, ms: Math.round(performance.now() - started) });
          return { content: [{ type: "text", text: JSON.stringify(result) }] };
        },
      );
    }

    // After the tools: the index reflects what this server instance registered.
    server.registerResource(
      "capability-index",
      "capability://index",
      { title: "Capability index", description: "Capabilities, tool definitions and skills this server offers.", mimeType: "application/json" },
      async (uri) => ({ contents: [{ uri: uri.href, mimeType: "application/json", text: JSON.stringify(await buildIndex(catalog, server)) }] }),
    );

    return server;
  }

  // Routes: GET /healthz and POST /mcp (Streamable HTTP, stateless, plain JSON responses). Reached only
  // through the auth gateway, which checks the phone's token; this server is on the private network.
  async function handle(req: IncomingMessage, res: ServerResponse): Promise<void> {
    const { pathname } = new URL(req.url ?? "/", "http://mcp");
    if (req.method === "GET" && pathname === "/healthz") return send(res, 200, { status: "ok", version: config.version });
    if (req.method !== "POST" || pathname !== "/mcp") return send(res, 404, { error: "Not found" });

    let body: unknown;
    try {
      body = JSON.parse((await readBody(req)).toString("utf8"));
    } catch (e) {
      if (e instanceof BodyTooLarge) {
        res.writeHead(413, { "content-type": "application/json", connection: "close" });
        res.end(JSON.stringify({ error: { message: "Request body too large", code: 413 } }));
        return;
      }
      return send(res, 400, { jsonrpc: "2.0", error: { code: -32700, message: "Parse error" }, id: null });
    }

    const server = buildServer();
    const transport = new StreamableHTTPServerTransport({ sessionIdGenerator: undefined, enableJsonResponse: true });
    res.on("close", () => {
      void transport.close();
      void server.close();
    });
    await server.connect(transport);
    await transport.handleRequest(req, res, body);
    log("request", { method: (body as { method?: string }).method, status: res.statusCode });
  }

  async function readBody(req: IncomingMessage): Promise<Buffer> {
    const chunks: Buffer[] = [];
    let size = 0;
    for await (const chunk of req as AsyncIterable<Buffer>) {
      size += chunk.length;
      if (size > config.maxBodyBytes) throw new BodyTooLarge();
      chunks.push(chunk);
    }
    return Buffer.concat(chunks);
  }


  return { buildServer, handle };
}

class BodyTooLarge extends Error {
  constructor() {
    super("Request body too large");
  }
}

export function send(res: ServerResponse, status: number, value: unknown): void {
  res.writeHead(status, { "content-type": "application/json" });
  res.end(JSON.stringify(value));
}
