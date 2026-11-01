import assert from "node:assert/strict";
import { describe, test } from "node:test";
import { loadConfig } from "../src/config.js";
import { ConfigError } from "../src/errors.js";

const base = {
  ISSUER_URL: "https://idp.example.invalid/application/o/samu/",
  AUDIENCE: "samu-audience",
  UPSTREAM_URL: "http://127.0.0.1:9000",
  UPSTREAM_VIRTUAL_KEY: "vk-placeholder",
};

describe("loadConfig", () => {
  test("applies defaults for everything optional", () => {
    assert.deepEqual(loadConfig(base), {
      version: "dev",
      port: 8080,
      issuerUrl: base.ISSUER_URL,
      jwksUrl: undefined,
      audience: "samu-audience",
      upstreamUrl: "http://127.0.0.1:9000",
      upstreamVirtualKey: "vk-placeholder",
      mcpUrl: undefined,
      rateLimit: { perMinute: 20, burst: 5 },
      logBodyRetentionMs: 24 * 3_600_000,
      logDir: "logs",
      debugDurationMs: 30 * 60_000,
      maxBodyBytes: 256 * 1024,
    });
  });

  test("reads every override", () => {
    const config = loadConfig({
      ...base,
      SAMU_VERSION: "1.2.3",
      PORT: "9090",
      JWKS_URL: "https://idp.example.invalid/jwks",
      MCP_URL: "http://127.0.0.1:9100",
      RATE_LIMIT_PER_MINUTE: "60",
      RATE_LIMIT_BURST: "10",
      LOG_BODY_RETENTION: "7d",
      LOG_DIR: "/var/log/gw",
      DEBUG_DURATION: "90s",
      MAX_BODY_BYTES: "1000",
    });
    assert.equal(config.version, "1.2.3");
    assert.equal(config.port, 9090);
    assert.equal(config.jwksUrl, "https://idp.example.invalid/jwks");
    assert.equal(config.mcpUrl, "http://127.0.0.1:9100");
    assert.deepEqual(config.rateLimit, { perMinute: 60, burst: 10 });
    assert.equal(config.logBodyRetentionMs, 7 * 86_400_000);
    assert.equal(config.logDir, "/var/log/gw");
    assert.equal(config.debugDurationMs, 90_000);
    assert.equal(config.maxBodyBytes, 1000);
  });

  test("each required variable missing is a ConfigError naming it", () => {
    for (const name of Object.keys(base)) {
      const env: Record<string, string> = { ...base };
      delete env[name];
      assert.throws(
        () => loadConfig(env),
        (e) => e instanceof ConfigError && e.message.includes(name),
        name,
      );
    }
  });

  test("empty required values count as missing", () => {
    assert.throws(() => loadConfig({ ...base, AUDIENCE: "" }), ConfigError);
  });

  test("the issuer and JWKS URLs must be https", () => {
    assert.throws(
      () => loadConfig({ ...base, ISSUER_URL: "http://idp.example.invalid/" }),
      /ISSUER_URL must be an https URL/,
    );
    assert.throws(
      () =>
        loadConfig({ ...base, JWKS_URL: "http://idp.example.invalid/jwks" }),
      /JWKS_URL must be an https URL/,
    );
  });

  test("URLs must parse", () => {
    assert.throws(
      () => loadConfig({ ...base, UPSTREAM_URL: "not a url" }),
      /UPSTREAM_URL is not a valid URL/,
    );
    assert.throws(
      () => loadConfig({ ...base, MCP_URL: "nope" }),
      /MCP_URL is not a valid URL/,
    );
  });

  test("trailing slashes are stripped from upstream URLs", () => {
    const config = loadConfig({
      ...base,
      UPSTREAM_URL: "http://127.0.0.1:9000///",
      MCP_URL: "http://127.0.0.1:9100/",
    });
    assert.equal(config.upstreamUrl, "http://127.0.0.1:9000");
    assert.equal(config.mcpUrl, "http://127.0.0.1:9100");
  });

  test("integers must be non-negative whole numbers; empty falls back to the default", () => {
    for (const bad of ["-1", "1.5", "abc"])
      assert.throws(
        () => loadConfig({ ...base, PORT: bad }),
        /PORT must be a non-negative integer/,
      );
    assert.equal(loadConfig({ ...base, PORT: "" }).port, 8080);
    assert.equal(
      loadConfig({ ...base, RATE_LIMIT_BURST: "0" }).rateLimit.burst,
      0,
    );
  });

  test("durations accept s, m, h, d and 0; anything else is an error", () => {
    const retention = (value: string) =>
      loadConfig({ ...base, LOG_BODY_RETENTION: value }).logBodyRetentionMs;
    assert.equal(retention("0"), 0);
    assert.equal(retention("45s"), 45_000);
    assert.equal(retention("30m"), 1_800_000);
    assert.equal(retention("24h"), 86_400_000);
    assert.equal(retention("2d"), 172_800_000);
    for (const bad of ["24", "h", "1w", "-5m", "1.5h", "5 m"])
      assert.throws(
        () => retention(bad),
        /LOG_BODY_RETENTION must look like/,
        bad,
      );
  });
});
