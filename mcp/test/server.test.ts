import assert from "node:assert/strict";
import { createServer, type Server } from "node:http";
import type { AddressInfo } from "node:net";
import { after, before, beforeEach, test } from "node:test";
import { Client } from "@modelcontextprotocol/sdk/client/index.js";
import { InMemoryTransport } from "@modelcontextprotocol/sdk/inMemory.js";
import type { CapabilityCatalog } from "../src/capabilities.js";
import type { Config } from "../src/config.js";
import { createHandlers } from "../src/server.js";
import { silenceLogs, stubFetch, testConfig } from "./helpers.js";

const catalog: CapabilityCatalog = {
  capabilities: [
    {
      id: "search",
      title: "Search",
      description: "Web",
      load: "lazy",
      tools: [{ name: "web_search", where: "remote" }],
    },
    {
      id: "weather",
      title: "Weather",
      description: "Weather",
      load: "lazy",
      tools: [
        { name: "get_weather", where: "hybrid" },
        { name: "get_weather_local", where: "local", required: false },
      ],
    },
    {
      id: "music",
      title: "Music",
      description: "Music",
      load: "lazy",
      tools: [{ name: "music_find", where: "remote" }],
    },
  ],
  skills: [
    {
      name: "weather-skill",
      description: "d",
      capabilities: ["weather"],
      instructions: "Be brief.",
    },
  ],
};

beforeEach(() => silenceLogs());

async function connect(config: Config): Promise<Client> {
  const server = createHandlers(config, catalog).buildServer();
  const [clientSide, serverSide] = InMemoryTransport.createLinkedPair();
  await server.connect(serverSide);
  const client = new Client({ name: "test-client", version: "0" });
  await client.connect(clientSide);
  return client;
}

const toolNames = async (config: Config) =>
  (await (await connect(config)).listTools()).tools.map((t) => t.name).sort();
/** The parts of the capability index these tests read. */
interface IndexShape {
  version: number;
  capabilities: { id: string }[];
  tools: { name: string }[];
  skills: { name: string }[];
}

const textOf = (r: { contents: object[] }) =>
  (r.contents[0] as { text: string }).text;
const firstText = (r: Awaited<ReturnType<Client["callTool"]>>) =>
  (r.content as { text: string }[])[0]!.text;

test("only web_search is offered with no optional config", async () => {
  assert.deepEqual(
    await toolNames(testConfig({ pirateWeatherApiKey: undefined })),
    ["web_search"],
  );
});

test("get_weather needs the Pirate Weather key", async () => {
  assert.deepEqual(await toolNames(testConfig()), [
    "get_weather",
    "web_search",
  ]);
});

test("music_find needs both the Plex URL and token", async () => {
  const base = { pirateWeatherApiKey: undefined };
  assert.ok(
    !(
      await toolNames(
        testConfig({ ...base, plexUrl: "https://127.0.0.1:32400" }),
      )
    ).includes("music_find"),
  );
  assert.ok(
    !(await toolNames(testConfig({ ...base, plexToken: "t" }))).includes(
      "music_find",
    ),
  );
  assert.ok(
    (
      await toolNames(
        testConfig({
          ...base,
          plexUrl: "https://127.0.0.1:32400",
          plexToken: "t",
        }),
      )
    ).includes("music_find"),
  );
});

test("tool annotations: remote reads are read-only, music is not open-world", async () => {
  const client = await connect(
    testConfig({ plexUrl: "https://127.0.0.1:32400", plexToken: "t" }),
  );
  const byName = new Map(
    (await client.listTools()).tools.map((t) => [t.name, t.annotations]),
  );
  assert.deepEqual(byName.get("web_search"), {
    readOnlyHint: true,
    openWorldHint: true,
  });
  assert.deepEqual(byName.get("get_weather"), {
    readOnlyHint: true,
    openWorldHint: true,
  });
  assert.deepEqual(byName.get("music_find"), {
    readOnlyHint: true,
    openWorldHint: false,
  });
});

test("web_search runs through the tool and returns JSON results", async () => {
  stubFetch(() => ({
    grounding: {
      generic: [
        { url: "https://a.example.invalid", title: "A", snippets: ["hello"] },
      ],
    },
  }));
  const result = await (
    await connect(testConfig())
  ).callTool({ name: "web_search", arguments: { query: "hi" } });
  assert.deepEqual(JSON.parse(firstText(result)), {
    results: [{ title: "A", url: "https://a.example.invalid", text: "hello" }],
  });
});

test("a failing upstream is an isError tool result", async () => {
  stubFetch(() => new Response("", { status: 500 }));
  const result = await (
    await connect(testConfig())
  ).callTool({ name: "web_search", arguments: { query: "hi" } });
  assert.equal(result.isError, true);
  assert.match(firstText(result), /Search service returned 500/);
});

test("capability://index lists only capabilities whose tools this config registered", async () => {
  const client = await connect(testConfig());
  const resources = (await client.listResources()).resources;
  assert.ok(resources.some((r) => r.uri === "capability://index"));
  const read = await client.readResource({ uri: "capability://index" });
  const content = read.contents[0] as { text: string; mimeType?: string };
  assert.equal(content.mimeType, "application/json");
  const index = JSON.parse(content.text) as IndexShape;
  assert.equal(index.version, 1);
  assert.deepEqual(
    index.capabilities.map((c) => c.id),
    ["search", "weather"],
  );
  assert.deepEqual(
    index.tools.map((t) => t.name),
    ["get_weather", "web_search"],
  );
  assert.deepEqual(
    index.skills.map((s) => s.name),
    ["weather-skill"],
  );
});

test("without the weather key the weather capability and its skill leave the index", async () => {
  const client = await connect(testConfig({ pirateWeatherApiKey: undefined }));
  const index = JSON.parse(
    textOf(await client.readResource({ uri: "capability://index" })),
  ) as IndexShape;
  assert.deepEqual(
    index.capabilities.map((c) => c.id),
    ["search"],
  );
  assert.deepEqual(index.skills, []);
});

// HTTP

let http: Server;
let base = "";
const maxBodyBytes = 2048;

before(async () => {
  const { handle } = createHandlers(testConfig({ maxBodyBytes }), catalog);
  http = createServer((req, res) => void handle(req, res));
  await new Promise<void>((resolve) => http.listen(0, "127.0.0.1", resolve));
  base = `http://127.0.0.1:${(http.address() as AddressInfo).port}`;
});
after(() => new Promise<void>((resolve) => http.close(() => resolve())));

const rpc = (body: string) =>
  fetch(`${base}/mcp`, {
    method: "POST",
    headers: {
      "content-type": "application/json",
      accept: "application/json, text/event-stream",
    },
    body,
  });

test("GET /healthz reports ok and the version", async () => {
  const res = await fetch(`${base}/healthz`);
  assert.equal(res.status, 200);
  assert.deepEqual(await res.json(), { status: "ok", version: "test" });
});

test("other routes and methods are 404", async () => {
  assert.equal(
    (await fetch(`${base}/v1/responses`, { method: "POST", body: "{}" }))
      .status,
    404,
  );
  assert.equal((await fetch(`${base}/mcp`)).status, 404);
  assert.equal(
    (await fetch(`${base}/healthz`, { method: "POST" })).status,
    404,
  );
});

test("POST /mcp answers with a plain JSON body, not an event stream", async () => {
  const res = await rpc(
    JSON.stringify({ jsonrpc: "2.0", id: 1, method: "tools/list" }),
  );
  assert.equal(res.status, 200);
  assert.match(res.headers.get("content-type") ?? "", /^application\/json/);
  const json = (await res.json()) as {
    id: number;
    result: { tools: { name: string }[] };
  };
  assert.equal(json.id, 1);
  assert.deepEqual(json.result.tools.map((t) => t.name).sort(), [
    "get_weather",
    "web_search",
  ]);
});

test("requests are stateless: no session id, and a second request needs no initialize", async () => {
  const first = await rpc(
    JSON.stringify({ jsonrpc: "2.0", id: 1, method: "tools/list" }),
  );
  assert.equal(first.headers.get("mcp-session-id"), null);
  const second = await rpc(
    JSON.stringify({ jsonrpc: "2.0", id: 2, method: "tools/list" }),
  );
  assert.equal(second.status, 200);
});

test("malformed JSON is a 400 parse error", async () => {
  const res = await rpc("{nope");
  assert.equal(res.status, 400);
  assert.deepEqual(await res.json(), {
    jsonrpc: "2.0",
    error: { code: -32700, message: "Parse error" },
    id: null,
  });
});

test("a body over the size limit is rejected", async () => {
  const big = JSON.stringify({
    jsonrpc: "2.0",
    id: 1,
    method: "tools/list",
    params: { pad: "x".repeat(maxBodyBytes) },
  });
  const res = await rpc(big);
  assert.equal(res.status, 413);
  assert.deepEqual(await res.json(), {
    error: { message: "Request body too large", code: 413 },
  });
});
