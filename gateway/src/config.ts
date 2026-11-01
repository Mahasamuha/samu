import { ConfigError } from "./errors.js";

export interface Config {
  /** Release version baked into the image; "dev" outside a CI build. */
  version: string;
  port: number;
  /** Issuer exactly as it appears in the token's `iss` claim. */
  issuerUrl: string;
  /** If unset, discovered from the issuer's OpenID configuration. */
  jwksUrl: string | undefined;
  audience: string;
  /** Base URL of the LLM gateway; the allowlisted inference path (e.g. `/v1/responses`) is appended. */
  upstreamUrl: string;
  upstreamVirtualKey: string;
  /** Base URL of the MCP server (remote tools); `/mcp` is appended. If unset, the route is 404. */
  mcpUrl: string | undefined;
  rateLimit: {
    perMinute: number;
    burst: number;
  };
  /** How long request/response bodies are kept on disk. 0 disables body logging (except in debug mode). */
  logBodyRetentionMs: number;
  logDir: string;
  debugDurationMs: number;
  maxBodyBytes: number;
}

export function loadConfig(env: NodeJS.ProcessEnv = process.env): Config {
  return {
    version: env.SAMU_VERSION || "dev",
    port: integer(env, "PORT", 8080),
    issuerUrl: httpsUrl(env, "ISSUER_URL"),
    jwksUrl: env.JWKS_URL ? httpsUrl(env, "JWKS_URL") : undefined,
    audience: required(env, "AUDIENCE"),
    upstreamUrl: url(env, "UPSTREAM_URL").replace(/\/+$/, ""),
    upstreamVirtualKey: required(env, "UPSTREAM_VIRTUAL_KEY"),
    mcpUrl: env.MCP_URL ? url(env, "MCP_URL").replace(/\/+$/, "") : undefined,
    rateLimit: {
      perMinute: integer(env, "RATE_LIMIT_PER_MINUTE", 20),
      burst: integer(env, "RATE_LIMIT_BURST", 5),
    },
    logBodyRetentionMs: duration(env, "LOG_BODY_RETENTION", "24h"),
    logDir: env.LOG_DIR ?? "logs",
    debugDurationMs: duration(env, "DEBUG_DURATION", "30m"),
    maxBodyBytes: integer(env, "MAX_BODY_BYTES", 256 * 1024),
  };
}

function required(env: NodeJS.ProcessEnv, name: string): string {
  const value = env[name];
  if (!value) throw new ConfigError(`${name} is required`);
  return value;
}

function url(env: NodeJS.ProcessEnv, name: string): string {
  const value = required(env, name);
  if (!URL.canParse(value)) throw new ConfigError(`${name} is not a valid URL`);
  return value;
}

/** Signing keys come from here, so plain http is refused. Upstream URLs stay http on the private network. */
function httpsUrl(env: NodeJS.ProcessEnv, name: string): string {
  const value = url(env, name);
  if (new URL(value).protocol !== "https:")
    throw new ConfigError(`${name} must be an https URL`);
  return value;
}

function integer(
  env: NodeJS.ProcessEnv,
  name: string,
  fallback: number,
): number {
  const raw = env[name];
  if (raw === undefined || raw === "") return fallback;
  const value = Number(raw);
  if (!Number.isInteger(value) || value < 0)
    throw new ConfigError(`${name} must be a non-negative integer`);
  return value;
}

const DURATION_UNITS_MS = {
  s: 1_000,
  m: 60_000,
  h: 3_600_000,
  d: 86_400_000,
} as const;

/** Parses "0", "90s", "30m", "24h", "7d". */
function duration(
  env: NodeJS.ProcessEnv,
  name: string,
  fallback: string,
): number {
  const raw = env[name] || fallback;
  if (raw === "0") return 0;
  const match = /^(\d+)([smhd])$/.exec(raw);
  if (!match)
    throw new ConfigError(`${name} must look like 30m, 24h or 7d (or 0)`);
  return (
    Number(match[1]) *
    DURATION_UNITS_MS[match[2] as keyof typeof DURATION_UNITS_MS]
  );
}
