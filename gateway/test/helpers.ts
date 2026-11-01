import { type ChildProcess, spawn } from "node:child_process";
import { createServer, type IncomingMessage, type Server, type ServerResponse } from "node:http";
import type { AddressInfo } from "node:net";
import path from "node:path";
import { exportJWK, generateKeyPair, type JWK, type CryptoKey, type KeyObject, SignJWT } from "jose";
import type { Config } from "../src/config.js";

export const ISSUER = "https://idp.example.invalid/application/o/samu/";
export const AUDIENCE = "samu-test-audience";

export type Key = CryptoKey | KeyObject;

export interface TestKey {
  kid: string;
  alg: string;
  privateKey: Key;
  jwk: JWK;
}

export async function makeKey(kid = "key-1", alg = "RS256"): Promise<TestKey> {
  const { privateKey, publicKey } = await generateKeyPair(alg, { extractable: true });
  const jwk = { ...(await exportJWK(publicKey)), kid, alg, use: "sig" };
  return { kid, alg, privateKey, jwk };
}

export interface TokenOptions {
  iss?: string;
  aud?: string | string[] | null;
  sub?: string | null;
  exp?: number | string | null;
  nbf?: number;
}

/** Signs a token that is valid by default; each option overrides (or, with null, omits) a claim. */
export async function signToken(key: TestKey, options: TokenOptions = {}): Promise<string> {
  const jwt = new SignJWT({}).setProtectedHeader({ alg: key.alg, kid: key.kid });
  jwt.setIssuer(options.iss ?? ISSUER);
  if (options.aud !== null) jwt.setAudience(options.aud ?? AUDIENCE);
  if (options.sub !== null) jwt.setSubject(options.sub ?? "user-1");
  if (options.exp !== null) jwt.setExpirationTime(options.exp ?? "5m");
  if (options.nbf !== undefined) jwt.setNotBefore(options.nbf);
  return jwt.sign(key.privateKey);
}

export function testConfig(overrides: Partial<Config> = {}): Config {
  return {
    version: "1.2.3",
    port: 0,
    issuerUrl: ISSUER,
    jwksUrl: undefined,
    audience: AUDIENCE,
    upstreamUrl: "http://127.0.0.1:1",
    upstreamVirtualKey: "vk-test-secret",
    mcpUrl: undefined,
    rateLimit: { perMinute: 600, burst: 100 },
    logBodyRetentionMs: 0,
    logDir: "logs",
    debugDurationMs: 1000,
    maxBodyBytes: 1024,
    ...overrides,
  };
}

export type Handler = (req: IncomingMessage, res: ServerResponse) => void;

export interface LocalServer {
  server: Server;
  url: string;
  close(): Promise<void>;
}

/** Starts an http server on 127.0.0.1 with an OS-assigned port. */
export async function listen(handler: Handler): Promise<LocalServer> {
  const server = createServer(handler);
  await new Promise<void>((resolve) => server.listen(0, "127.0.0.1", resolve));
  const { port } = server.address() as AddressInfo;
  return {
    server,
    url: `http://127.0.0.1:${port}`,
    close: () =>
      new Promise<void>((resolve) => {
        server.closeAllConnections();
        server.close(() => resolve());
      }),
  };
}

/** Serves a JWKS at /jwks and counts requests; `keys` can be changed between requests. */
export async function jwksServer(keys: TestKey[]): Promise<LocalServer & { hits: () => number; keys: TestKey[] }> {
  let hits = 0;
  const state = { keys };
  const local = await listen((_req, res) => {
    hits++;
    res.writeHead(200, { "content-type": "application/json" });
    res.end(JSON.stringify({ keys: state.keys.map((k) => k.jwk) }));
  });
  return Object.assign(local, { hits: () => hits, keys: state.keys });
}

export async function readAll(req: IncomingMessage): Promise<Buffer> {
  const chunks: Buffer[] = [];
  for await (const chunk of req) chunks.push(chunk as Buffer);
  return Buffer.concat(chunks);
}

export interface RunningGateway {
  url: string;
  /** Everything the process wrote to stdout and stderr so far. */
  output(): string;
  signal(name: NodeJS.Signals): void;
  stop(): Promise<void>;
}

/**
 * Runs the real gateway entry point as a child process (it reads its config at import time).
 * The placeholder https issuer is redirected to `idpUrl` by the fetchRewrite preload.
 */
export async function startGateway(idpUrl: string, env: Record<string, string>): Promise<RunningGateway> {
  const port = await freePort();
  const root = path.dirname(new URL(import.meta.url).pathname);
  let output = "";
  const child: ChildProcess = spawn(process.execPath, ["--import", path.join(root, "fetchRewrite.js"), path.join(root, "../src/index.js")], {
    env: {
      PATH: process.env.PATH ?? "",
      PORT: String(port),
      ISSUER_URL: ISSUER,
      JWKS_URL: `${ISSUER}jwks`,
      AUDIENCE,
      UPSTREAM_VIRTUAL_KEY: "vk-test-secret",
      TEST_FETCH_FROM: ISSUER,
      TEST_FETCH_TO: `${idpUrl}/`,
      ...env,
    },
    stdio: ["ignore", "pipe", "pipe"],
  });
  child.stdout?.on("data", (d: Buffer) => (output += d));
  child.stderr?.on("data", (d: Buffer) => (output += d));

  await waitFor(() => output.includes('"event":"listening"') || child.exitCode !== null);
  if (child.exitCode !== null) throw new Error(`gateway exited at startup: ${output}`);
  return { url: `http://127.0.0.1:${port}`, output: () => output, signal: (s) => child.kill(s), stop: () => stopChild(child) };
}

function stopChild(child: ChildProcess): Promise<void> {
  return new Promise((resolve) => {
    if (child.exitCode !== null) return resolve();
    child.once("exit", () => resolve());
    child.kill("SIGKILL");
  });
}

export async function freePort(): Promise<number> {
  const local = await listen(() => {});
  const port = Number(new URL(local.url).port);
  await local.close();
  return port;
}

export async function waitFor(condition: () => boolean | Promise<boolean>, timeoutMs = 5000): Promise<void> {
  const deadline = Date.now() + timeoutMs;
  while (!(await condition())) {
    if (Date.now() > deadline) throw new Error("timed out waiting for condition");
    await new Promise((r) => setTimeout(r, 10));
  }
}
