import { once } from "node:events";
import type { IncomingMessage, ServerResponse } from "node:http";
import type { ResponseCapture } from "./capture.js";
import { HttpError } from "./errors.js";
import { log } from "./logging.js";

/** Status recorded when the client disconnects before the response completes. */
export const CLIENT_CLOSED = 499;

/** Where an allowlisted route goes, with any headers the gateway adds (e.g. the LLM gateway's key). */
export interface Upstream {
  url: string;
  headers: Record<string, string>;
}

/** Request headers passed through from the phone; everything else (notably its token) stays here. */
const FORWARDED_REQUEST_HEADERS = ["accept", "mcp-protocol-version"] as const;

/**
 * Forwards a request to its upstream and streams the response back byte for byte. Only content-type is
 * forwarded from upstream: upstream services' own headers stay here.
 */
export async function forward(
  upstreamTarget: Upstream,
  req: IncomingMessage,
  requestId: string,
  body: Buffer<ArrayBuffer>,
  res: ServerResponse,
  capture: ResponseCapture | undefined,
): Promise<number> {
  // Client hang-ups cancel the upstream call so an abandoned answer stops costing tokens.
  const abort = new AbortController();
  const onClose = () => abort.abort();
  res.once("close", onClose);

  try {
    let upstream: Response;
    try {
      const passed = Object.fromEntries(
        FORWARDED_REQUEST_HEADERS.flatMap((name) =>
          typeof req.headers[name] === "string"
            ? [[name, req.headers[name]]]
            : [],
        ),
      );
      upstream = await fetch(upstreamTarget.url, {
        method: "POST",
        headers: {
          ...passed,
          "content-type": "application/json",
          ...upstreamTarget.headers,
        },
        body,
        signal: abort.signal,
      });
    } catch (e) {
      if (abort.signal.aborted) return CLIENT_CLOSED;
      log("upstream_unreachable", { requestId, error: String(e) });
      throw new HttpError(502, "Upstream unavailable");
    }

    // Upstream auth failures are a server misconfiguration. Passed through, the phone would read them as its own token being rejected.
    if (upstream.status === 401 || upstream.status === 403) {
      // The body says what's misconfigured; any key it echoes is redacted before it reaches the container log.
      const body = (await upstream.text())
        .slice(0, 500)
        .replace(/sk-[\w-]+/g, "sk-<redacted>");
      log("upstream_rejected_gateway", {
        requestId,
        status: upstream.status,
        body,
      });
      throw new HttpError(502, "Upstream rejected the request");
    }

    const contentType =
      upstream.headers.get("content-type") ?? "application/json";
    res.writeHead(upstream.status, {
      "content-type": contentType,
      "cache-control": "no-store",
      ...(contentType.startsWith("text/event-stream")
        ? { "x-accel-buffering": "no" }
        : {}),
    });

    try {
      for await (const chunk of upstream.body ?? []) {
        capture?.add(chunk);
        if (!res.write(chunk))
          await once(res, "drain", { signal: abort.signal });
      }
      res.end();
    } catch (e) {
      if (abort.signal.aborted) return CLIENT_CLOSED;
      throw e;
    }
    return upstream.status;
  } finally {
    res.off("close", onClose);
  }
}
