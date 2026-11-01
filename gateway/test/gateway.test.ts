// End-to-end tests: the real gateway process (src/index.ts reads its config at import time) against local fake
// identity-provider and upstream servers.
import assert from "node:assert/strict";
import {
  mkdir,
  mkdtemp,
  readdir,
  readFile,
  rm,
  utimes,
  writeFile,
} from "node:fs/promises";
import { tmpdir } from "node:os";
import path from "node:path";
import { after, afterEach, before, describe, test } from "node:test";
import {
  jwksServer,
  listen,
  makeKey,
  readAll,
  signToken,
  startGateway,
  waitFor,
  type Handler,
  type LocalServer,
  type RunningGateway,
  type TestKey,
} from "./helpers.js";

const PHONE_TOKEN_MARKER = "phone-token";
const VIRTUAL_KEY = "vk-test-secret";

interface Seen {
  method: string | undefined;
  url: string | undefined;
  headers: Record<string, string | string[] | undefined>;
  body: string;
}

describe("gateway", () => {
  let key: TestKey;
  let idp: LocalServer;
  let token: string;
  let tmp: string;

  const cleanups: Array<() => Promise<void>> = [];
  before(async () => {
    key = await makeKey();
    idp = await jwksServer([key]);
    token = await signToken(key, { sub: PHONE_TOKEN_MARKER });
    tmp = await mkdtemp(path.join(tmpdir(), "gateway-test-"));
  });
  after(async () => {
    await idp.close();
    await rm(tmp, { recursive: true, force: true });
  });
  afterEach(async () => {
    for (const cleanup of cleanups.splice(0)) await cleanup();
  });

  async function upstream(handler: Handler): Promise<LocalServer> {
    const server = await listen(handler);
    cleanups.push(() => server.close());
    return server;
  }

  async function gateway(
    env: Record<string, string> = {},
  ): Promise<RunningGateway & { logDir: string }> {
    const logDir = await mkdtemp(path.join(tmp, "logs-"));
    const gw = await startGateway(idp.url, {
      LOG_DIR: logDir,
      LOG_BODY_RETENTION: "0",
      UPSTREAM_URL: "http://127.0.0.1:1",
      ...env,
    });
    cleanups.push(() => gw.stop());
    return Object.assign(gw, { logDir });
  }

  /** An upstream that records what it receives and answers with `respond`. */
  async function recordingUpstream(
    respond: Handler = (_req, res) =>
      void res.writeHead(200, { "content-type": "application/json" }).end("{}"),
  ) {
    const seen: Seen[] = [];
    // The handler type returns void, so the async work is detached; readAll can't fail on a local request.
    const server = await upstream((req, res) => {
      void readAll(req).then((body) => {
        seen.push({
          method: req.method,
          url: req.url,
          headers: req.headers,
          body: body.toString("utf8"),
        });
        respond(req, res);
      });
    });
    return { server, seen };
  }

  const post = (
    gw: RunningGateway,
    route: string,
    body: unknown = { input: "hi" },
    headers: Record<string, string> = {},
  ) =>
    fetch(gw.url + route, {
      method: "POST",
      headers: {
        authorization: `Bearer ${token}`,
        "content-type": "application/json",
        ...headers,
      },
      body: typeof body === "string" ? body : JSON.stringify(body),
    });

  describe("route allowlist", () => {
    test("GET /healthz needs no token and reports the version", async () => {
      const gw = await gateway({ SAMU_VERSION: "9.8.7" });
      const res = await fetch(`${gw.url}/healthz`);
      assert.equal(res.status, 200);
      assert.deepEqual(await res.json(), { status: "ok", version: "9.8.7" });
    });

    test("the version defaults to dev", async () => {
      const gw = await gateway();
      assert.equal(
        (
          (await (await fetch(`${gw.url}/healthz`)).json()) as {
            version: string;
          }
        ).version,
        "dev",
      );
    });

    test("anything outside the allowlist is 404, even with a valid token", async () => {
      const up = await recordingUpstream();
      const mcp = await recordingUpstream();
      const gw = await gateway({
        UPSTREAM_URL: up.server.url,
        MCP_URL: mcp.server.url,
      });
      const auth = { authorization: `Bearer ${token}` };

      const attempts: Array<[string, string]> = [
        ["POST", "/healthz"],
        ["GET", "/v1/responses"],
        ["PUT", "/v1/responses"],
        ["DELETE", "/mcp"],
        ["GET", "/mcp"],
        ["POST", "/v1/chat/completions"],
        ["POST", "/v1/models"],
        ["POST", "/"],
        ["GET", "/"],
        ["POST", "/v1/responses/"],
        ["POST", "/V1/responses"],
        ["POST", "/v1/responses/extra"],
        ["POST", "/mcp/extra"],
        ["GET", "/metrics"],
        ["GET", "/healthz/"],
      ];
      for (const [method, route] of attempts) {
        const res = await fetch(gw.url + route, {
          method,
          headers: auth,
          body: method === "POST" ? "{}" : undefined,
        });
        assert.equal(res.status, 404, `${method} ${route}`);
      }
      assert.equal(
        up.seen.length + mcp.seen.length,
        0,
        "nothing reached an upstream",
      );
    });

    test("a double-slash path is not read as an allowlisted route", async () => {
      const up = await recordingUpstream();
      const gw = await gateway({ UPSTREAM_URL: up.server.url });
      // fetch normalizes the URL, so send the raw request target over a plain socket.
      const { connect } = await import("node:net");
      const status = await new Promise<string>((resolve, reject) => {
        const socket = connect(
          Number(new URL(gw.url).port),
          "127.0.0.1",
          () => {
            socket.write(
              `POST //v1/responses HTTP/1.1\r\nhost: x\r\nauthorization: Bearer ${token}\r\ncontent-type: application/json\r\ncontent-length: 2\r\nconnection: close\r\n\r\n{}`,
            );
          },
        );
        socket.once("data", (d) =>
          resolve(d.toString().split("\r\n")[0] ?? ""),
        );
        socket.once("error", reject);
      });
      assert.match(status, /404/);
      assert.equal(up.seen.length, 0);
    });

    test("/mcp is 404 when no MCP server is configured", async () => {
      const gw = await gateway();
      assert.equal((await post(gw, "/mcp")).status, 404);
    });

    test("a query string does not change which route matches", async () => {
      const up = await recordingUpstream();
      const gw = await gateway({ UPSTREAM_URL: up.server.url });
      assert.equal((await post(gw, "/v1/responses?x=1")).status, 200);
      assert.equal(
        up.seen[0]?.url,
        "/v1/responses",
        "the query is not forwarded",
      );
    });

    test("allowlisted routes require a valid token", async () => {
      const up = await recordingUpstream();
      const mcp = await recordingUpstream();
      const gw = await gateway({
        UPSTREAM_URL: up.server.url,
        MCP_URL: mcp.server.url,
      });
      const bad = await signToken(await makeKey("other"), {});
      for (const route of ["/v1/responses", "/mcp"]) {
        for (const authorization of [
          undefined,
          "Bearer",
          "Bearer garbage",
          `Bearer ${bad}`,
        ]) {
          const res = await fetch(gw.url + route, {
            method: "POST",
            headers: {
              "content-type": "application/json",
              ...(authorization ? { authorization } : {}),
            },
            body: "{}",
          });
          assert.equal(
            res.status,
            401,
            `${route} with ${String(authorization)}`,
          );
          assert.ok(res.headers.get("www-authenticate"));
        }
      }
      assert.equal(up.seen.length + mcp.seen.length, 0);
    });
  });

  describe("request handling", () => {
    test("rejects bodies that are not a JSON object", async () => {
      const up = await recordingUpstream();
      const gw = await gateway({ UPSTREAM_URL: up.server.url });
      for (const body of ["not json", "[1,2]", "null", "42", ""]) {
        assert.equal(
          (await post(gw, "/v1/responses", body)).status,
          400,
          `body ${body}`,
        );
      }
      assert.equal(up.seen.length, 0);
    });

    test("rejects bodies over the size limit with 413", async () => {
      const up = await recordingUpstream();
      const gw = await gateway({
        UPSTREAM_URL: up.server.url,
        MAX_BODY_BYTES: "100",
      });
      const res = await post(gw, "/v1/responses", { input: "x".repeat(500) });
      assert.equal(res.status, 413);
      assert.equal(up.seen.length, 0);
      assert.equal(
        (await post(gw, "/v1/responses", { input: "ok" })).status,
        200,
      );
    });

    test("rate-limits per subject with 429 and a retry-after", async () => {
      const up = await recordingUpstream();
      const gw = await gateway({
        UPSTREAM_URL: up.server.url,
        RATE_LIMIT_BURST: "2",
        RATE_LIMIT_PER_MINUTE: "1",
      });
      assert.equal((await post(gw, "/v1/responses")).status, 200);
      assert.equal((await post(gw, "/v1/responses")).status, 200);
      const limited = await post(gw, "/v1/responses");
      assert.equal(limited.status, 429);
      assert.ok(Number(limited.headers.get("retry-after")) > 0);

      const otherUser = await signToken(key, { sub: "someone-else" });
      const res = await post(
        gw,
        "/v1/responses",
        { input: "hi" },
        { authorization: `Bearer ${otherUser}` },
      );
      assert.equal(res.status, 200, "another subject has its own bucket");
    });

    test("error responses are JSON with an error message", async () => {
      const gw = await gateway();
      const res = await fetch(`${gw.url}/nope`);
      assert.match(res.headers.get("content-type") ?? "", /application\/json/);
      assert.deepEqual(await res.json(), {
        error: { message: "Not found", code: 404 },
      });
    });
  });

  describe("upstream request", () => {
    test("/v1/responses gets the virtual key, and the phone's token is not forwarded", async () => {
      const up = await recordingUpstream();
      const gw = await gateway({ UPSTREAM_URL: `${up.server.url}/` });
      const res = await post(
        gw,
        "/v1/responses",
        { input: "hello", stream: true },
        {
          accept: "text/event-stream",
          cookie: "session=secret",
          "x-forwarded-for": "10.0.0.9",
          "x-custom": "1",
        },
      );
      assert.equal(res.status, 200);

      const seen = up.seen[0];
      assert.ok(seen);
      assert.equal(seen.method, "POST");
      assert.equal(seen.url, "/v1/responses");
      assert.equal(seen.headers.authorization, `Bearer ${VIRTUAL_KEY}`);
      assert.equal(seen.headers["content-type"], "application/json");
      assert.equal(seen.headers.accept, "text/event-stream");
      assert.deepEqual(JSON.parse(seen.body), { input: "hello", stream: true });
      for (const name of ["cookie", "x-forwarded-for", "x-custom"])
        assert.equal(seen.headers[name], undefined, `${name} is not forwarded`);
      assert.ok(
        !JSON.stringify(seen.headers).includes(token),
        "the phone's token never reaches upstream",
      );
    });

    test("/mcp is forwarded with no authorization at all, but keeps the protocol version", async () => {
      const mcp = await recordingUpstream();
      const gw = await gateway({ MCP_URL: `${mcp.server.url}/` });
      const res = await post(
        gw,
        "/mcp",
        { jsonrpc: "2.0", id: 1, method: "tools/list" },
        { "mcp-protocol-version": "2025-06-18" },
      );
      assert.equal(res.status, 200);

      const seen = mcp.seen[0];
      assert.ok(seen);
      assert.equal(seen.url, "/mcp");
      assert.equal(seen.headers.authorization, undefined);
      assert.equal(seen.headers["mcp-protocol-version"], "2025-06-18");
    });
  });

  describe("upstream response", () => {
    test("only content-type comes back from upstream", async () => {
      const up = await recordingUpstream((_req, res) => {
        res.writeHead(200, {
          "content-type": "text/event-stream",
          "set-cookie": "leak=1",
          "x-bifrost-provider": "provider-account-id",
          server: "upstream-server",
          "x-request-id": "upstream-request",
        });
        res.end('data: {"type":"response.completed"}\n\n');
      });
      const gw = await gateway({ UPSTREAM_URL: up.server.url });
      const res = await post(gw, "/v1/responses");
      assert.equal(res.headers.get("content-type"), "text/event-stream");
      for (const name of [
        "set-cookie",
        "x-bifrost-provider",
        "server",
        "x-request-id",
      ])
        assert.equal(res.headers.get(name), null, name);
      assert.equal(res.headers.get("cache-control"), "no-store");
      assert.equal(res.headers.get("x-accel-buffering"), "no");
    });

    test("upstream status and body pass through; a missing content-type defaults to JSON", async () => {
      const up = await recordingUpstream((_req, res) => {
        res.statusCode = 429;
        res.end('{"error":"slow down"}');
      });
      const gw = await gateway({ UPSTREAM_URL: up.server.url });
      const res = await post(gw, "/v1/responses");
      assert.equal(res.status, 429);
      assert.equal(res.headers.get("content-type"), "application/json");
      assert.equal(
        res.headers.get("x-accel-buffering"),
        null,
        "only event streams get the proxy-buffering hint",
      );
      assert.equal(await res.text(), '{"error":"slow down"}');
    });

    test("upstream 401 and 403 become 502, so the phone does not take them for its own token failing", async () => {
      for (const status of [401, 403]) {
        const up = await recordingUpstream(
          (_req, res) =>
            void res.writeHead(status).end(`bad key sk-ant-secret-123`),
        );
        const gw = await gateway({ UPSTREAM_URL: up.server.url });
        const res = await post(gw, "/v1/responses");
        assert.equal(res.status, 502, `upstream ${status}`);
        assert.ok(
          !(await res.text()).includes("sk-ant"),
          "upstream body is not passed on",
        );
        assert.ok(
          !gw.output().includes("sk-ant-secret-123"),
          "keys echoed by upstream are redacted in the log",
        );
        assert.match(gw.output(), /sk-<redacted>/);
      }
    });

    test("an unreachable upstream gives 502", async () => {
      const gw = await gateway({ UPSTREAM_URL: "http://127.0.0.1:1" });
      assert.equal((await post(gw, "/v1/responses")).status, 502);
    });

    test("streams chunks through as they arrive, without buffering", async () => {
      let release!: () => void;
      const held = new Promise<void>((resolve) => (release = resolve));
      let upstreamEnded = false;
      const up = await recordingUpstream((_req, res) => {
        res.writeHead(200, { "content-type": "text/event-stream" });
        res.write("data: first\n\n");
        void held.then(() => {
          res.end("data: last\n\n");
          upstreamEnded = true;
        });
      });
      const gw = await gateway({ UPSTREAM_URL: up.server.url });

      const res = await post(gw, "/v1/responses", { stream: true });
      assert.equal(res.status, 200);
      const reader = res.body!.getReader();
      const first = await reader.read();
      assert.equal(new TextDecoder().decode(first.value), "data: first\n\n");
      assert.equal(
        upstreamEnded,
        false,
        "the first chunk arrived while upstream was still open",
      );

      release();
      let rest = "";
      for (let r = await reader.read(); !r.done; r = await reader.read())
        rest += new TextDecoder().decode(r.value);
      assert.equal(rest, "data: last\n\n");
    });

    test("a client that hangs up cancels the upstream request", async () => {
      let upstreamClosed = false;
      const up = await recordingUpstream((req, res) => {
        res.writeHead(200, { "content-type": "text/event-stream" });
        res.write("data: first\n\n");
        res.on("close", () => (upstreamClosed = true));
        req.on("close", () => (upstreamClosed = true));
      });
      const gw = await gateway({ UPSTREAM_URL: up.server.url });
      const res = await post(gw, "/v1/responses", { stream: true });
      const reader = res.body!.getReader();
      await reader.read();
      await reader.cancel();
      await waitFor(() => upstreamClosed);
      await waitFor(() => gw.output().includes('"status":499'));
    });
  });

  describe("logging", () => {
    const sseBody = [
      'data: {"type":"response.output_text.delta","delta":"Hel"}',
      'data: {"type":"response.output_text.delta","delta":"lo"}',
      'data: {"type":"response.completed","response":{"usage":{"total_tokens":7}}}',
      "",
    ].join("\n\n");
    const sseUpstream = () =>
      recordingUpstream(
        (_req, res) =>
          void res
            .writeHead(200, { "content-type": "text/event-stream" })
            .end(sseBody),
      );

    async function bodyRecords(
      dir: string,
    ): Promise<Array<Record<string, unknown>>> {
      const names = (await readdir(dir)).filter((n) => n.startsWith("bodies-"));
      const records: Array<Record<string, unknown>> = [];
      for (const name of names) {
        const text = await readFile(path.join(dir, name), "utf8");
        // A line may still be mid-write; only complete lines count.
        for (const line of text.split("\n")) {
          try {
            records.push(JSON.parse(line) as Record<string, unknown>);
          } catch {
            // incomplete or empty
          }
        }
      }
      return records;
    }

    test("bearer tokens and the virtual key never appear in stdout or body logs", async () => {
      const up = await sseUpstream();
      const gw = await gateway({
        UPSTREAM_URL: up.server.url,
        LOG_BODY_RETENTION: "1h",
      });
      await post(gw, "/v1/responses", { input: "hi" });
      await fetch(`${gw.url}/v1/responses`, {
        method: "POST",
        headers: { authorization: "Bearer not-a-valid-token-xyz" },
        body: "{}",
      });
      await waitFor(() => gw.output().includes('"status":401'));
      await waitFor(async () => (await bodyRecords(gw.logDir)).length > 0);

      const everything =
        gw.output() + JSON.stringify(await bodyRecords(gw.logDir));
      for (const secret of [
        token,
        token.split(".")[1]!,
        "not-a-valid-token-xyz",
        VIRTUAL_KEY,
        "authorization",
      ]) {
        assert.ok(
          !everything.toLowerCase().includes(secret.toLowerCase()),
          `log contains ${secret.slice(0, 12)}…`,
        );
      }
      assert.match(gw.output(), /"event":"request"/);
    });

    test("with retention enabled, a body record holds the request and the assembled response", async () => {
      const up = await sseUpstream();
      const gw = await gateway({
        UPSTREAM_URL: up.server.url,
        LOG_BODY_RETENTION: "1h",
      });
      await (await post(gw, "/v1/responses", { input: "hi" })).text();
      await waitFor(async () => (await bodyRecords(gw.logDir)).length > 0);

      const [record] = await bodyRecords(gw.logDir);
      assert.ok(record);
      assert.deepEqual(record.request, { input: "hi" });
      assert.equal(record.status, 200);
      assert.equal(record.subject, PHONE_TOKEN_MARKER);
      assert.equal(record.debug, false);
      const response = record.response as Record<string, unknown>;
      assert.equal(response.text, "Hello");
      assert.deepEqual(response.usage, { total_tokens: 7 });
      assert.equal(
        response.raw,
        undefined,
        "raw events are only kept in debug mode",
      );
    });

    test("with retention 0, no bodies are written", async () => {
      const up = await sseUpstream();
      const gw = await gateway({ UPSTREAM_URL: up.server.url });
      await (await post(gw, "/v1/responses")).text();
      await waitFor(() => gw.output().includes('"event":"request"'));
      assert.deepEqual(
        (await readdir(gw.logDir)).filter((n) => n.startsWith("bodies-")),
        [],
      );
    });

    test("SIGUSR2 turns debug on: bodies are logged with raw events, and a second signal turns it off", async () => {
      const up = await sseUpstream();
      const gw = await gateway({
        UPSTREAM_URL: up.server.url,
        DEBUG_DURATION: "1h",
      });
      gw.signal("SIGUSR2");
      await waitFor(() => gw.output().includes('"event":"debug_on"'));
      await (await post(gw, "/v1/responses")).text();
      await waitFor(async () => (await bodyRecords(gw.logDir)).length > 0);
      const [record] = await bodyRecords(gw.logDir);
      assert.equal(record?.debug, true);
      const response = record?.response as Record<string, unknown> | undefined;
      assert.match(String(response?.raw), /response\.output_text\.delta/);

      gw.signal("SIGUSR2");
      await waitFor(() => gw.output().includes('"event":"debug_off"'));
      assert.match(gw.output(), /"reason":"toggled"/);
    });

    test("startup removes body logs older than the retention and keeps newer and unrelated files", async () => {
      const dir = await mkdtemp(path.join(tmp, "prune-"));
      await mkdir(dir, { recursive: true });
      const old = path.join(dir, "bodies-2020-01-01T00.jsonl");
      const fresh = path.join(dir, "bodies-2099-01-01T00.jsonl");
      const unrelated = path.join(dir, "notes.txt");
      for (const f of [old, fresh, unrelated]) await writeFile(f, "{}\n");
      const longAgo = new Date(Date.now() - 3 * 3_600_000);
      for (const f of [old, unrelated]) await utimes(f, longAgo, longAgo);

      await gateway({ LOG_DIR: dir, LOG_BODY_RETENTION: "1h" });
      assert.deepEqual((await readdir(dir)).sort(), [
        "bodies-2099-01-01T00.jsonl",
        "notes.txt",
      ]);
    });
  });
});
