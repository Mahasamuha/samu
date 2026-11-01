import assert from "node:assert/strict";
import { after, before, describe, test } from "node:test";
import { SignJWT } from "jose";
import { TokenVerifier } from "../src/auth.js";
import { HttpError } from "../src/errors.js";
import { AUDIENCE, ISSUER, jwksServer, listen, makeKey, signToken, testConfig, type LocalServer, type TestKey } from "./helpers.js";

async function rejection(promise: Promise<unknown>): Promise<HttpError> {
  try {
    await promise;
  } catch (e) {
    assert.ok(e instanceof HttpError, `expected HttpError, got ${String(e)}`);
    return e;
  }
  assert.fail("expected the call to be rejected");
}

describe("TokenVerifier", () => {
  let key: TestKey;
  let jwks: LocalServer;
  let verifier: TokenVerifier;

  before(async () => {
    key = await makeKey("key-1");
    jwks = await jwksServer([key]);
    verifier = new TokenVerifier(testConfig({ jwksUrl: `${jwks.url}/jwks` }));
  });
  after(() => jwks.close());

  const bearer = (token: string) => `Bearer ${token}`;
  const now = () => Math.floor(Date.now() / 1000);

  async function assertInvalid(token: string): Promise<void> {
    const e = await rejection(verifier.verify(bearer(token)));
    assert.equal(e.status, 401);
    assert.equal(e.headers["www-authenticate"], 'Bearer error="invalid_token"');
  }

  test("accepts a valid token and returns its subject", async () => {
    const identity = await verifier.verify(bearer(await signToken(key, { sub: "alice" })));
    assert.deepEqual(identity, { subject: "alice" });
  });

  test("the scheme is case-insensitive", async () => {
    const identity = await verifier.verify(`bearer ${await signToken(key)}`);
    assert.equal(identity.subject, "user-1");
  });

  test("rejects a missing or malformed authorization header with 401 and a challenge", async () => {
    const token = await signToken(key);
    for (const header of [undefined, "", "Bearer", "Bearer ", `Basic ${token}`, token, `Bearer ${token} extra`]) {
      const e = await rejection(verifier.verify(header));
      assert.equal(e.status, 401, `header ${String(header)}`);
      assert.equal(e.headers["www-authenticate"], "Bearer");
    }
  });

  test("rejects garbage that is not a JWT", async () => {
    await assertInvalid("not-a-jwt");
    await assertInvalid("a.b.c");
  });

  test("rejects the wrong issuer", async () => {
    await assertInvalid(await signToken(key, { iss: "https://other.example.invalid/" }));
  });

  test("rejects the wrong audience", async () => {
    await assertInvalid(await signToken(key, { aud: "someone-else" }));
  });

  test("rejects a missing audience", async () => {
    await assertInvalid(await signToken(key, { aud: null }));
  });

  test("rejects an audience array, even one containing the expected value", async () => {
    await assertInvalid(await signToken(key, { aud: [AUDIENCE] }));
    await assertInvalid(await signToken(key, { aud: [AUDIENCE, "other"] }));
  });

  test("rejects an expired token but allows 30 seconds of clock skew", async () => {
    await assertInvalid(await signToken(key, { exp: now() - 120 }));
    const slightlyExpired = await verifier.verify(bearer(await signToken(key, { exp: now() - 10 })));
    assert.equal(slightlyExpired.subject, "user-1");
  });

  test("rejects a token with no expiry", async () => {
    await assertInvalid(await signToken(key, { exp: null }));
  });

  test("rejects a token with no subject", async () => {
    await assertInvalid(await signToken(key, { sub: null }));
  });

  test("rejects a token that is not valid yet (nbf beyond the skew)", async () => {
    await assertInvalid(await signToken(key, { nbf: now() + 300 }));
  });

  test("rejects a token signed by a different key with the same kid", async () => {
    const impostor = await makeKey("key-1");
    await assertInvalid(await signToken(impostor));
  });

  test("rejects a token with an unknown kid", async () => {
    await assertInvalid(await signToken(await makeKey("unknown-kid")));
  });

  test("rejects HS256 tokens (symmetric algorithms are not allowed)", async () => {
    const token = await new SignJWT({})
      .setProtectedHeader({ alg: "HS256", kid: key.kid })
      .setIssuer(ISSUER)
      .setAudience(AUDIENCE)
      .setSubject("user-1")
      .setExpirationTime("5m")
      .sign(new TextEncoder().encode("a-shared-secret-of-sufficient-length"));
    await assertInvalid(token);
  });

  test("rejects an unsigned token (alg none)", async () => {
    const encode = (v: unknown) => Buffer.from(JSON.stringify(v)).toString("base64url");
    const token = `${encode({ alg: "none" })}.${encode({ iss: ISSUER, aud: AUDIENCE, sub: "u", exp: now() + 300 })}.`;
    await assertInvalid(token);
  });

  test("accepts other allowed asymmetric algorithms (ES256)", async () => {
    const ec = await makeKey("ec-1", "ES256");
    const server = await jwksServer([key, ec]);
    try {
      const v = new TokenVerifier(testConfig({ jwksUrl: `${server.url}/jwks` }));
      assert.equal((await v.verify(bearer(await signToken(ec)))).subject, "user-1");
    } finally {
      await server.close();
    }
  });

  test("returns 503 when the key set endpoint answers with an error", async () => {
    const broken = await listen((_req, res) => {
      res.writeHead(500).end("nope");
    });
    try {
      const v = new TokenVerifier(testConfig({ jwksUrl: `${broken.url}/jwks` }));
      const e = await rejection(v.verify(bearer(await signToken(key))));
      assert.equal(e.status, 503);
    } finally {
      await broken.close();
    }
  });

  test("returns 503 when the key set endpoint is unreachable", async () => {
    const v = new TokenVerifier(testConfig({ jwksUrl: "http://127.0.0.1:1/jwks" }));
    const e = await rejection(v.verify(bearer(await signToken(key))));
    assert.equal(e.status, 503);
  });
});

describe("TokenVerifier JWKS discovery", () => {
  test("discovers the JWKS URL from the issuer's OpenID configuration", async () => {
    const key = await makeKey();
    let base = "";
    let discoveryHits = 0;
    const idp = await listen((req, res) => {
      res.writeHead(200, { "content-type": "application/json" });
      if (req.url === "/.well-known/openid-configuration") {
        discoveryHits++;
        res.end(JSON.stringify({ issuer: `${base}/`, jwks_uri: `${base}/keys` }));
      } else if (req.url === "/keys") {
        res.end(JSON.stringify({ keys: [key.jwk] }));
      } else {
        res.writeHead(404).end();
      }
    });
    base = idp.url;
    try {
      const issuer = `${base}/`;
      const verifier = new TokenVerifier(testConfig({ issuerUrl: issuer }));
      const token = await signToken(key, { iss: issuer });
      assert.equal((await verifier.verify(`Bearer ${token}`)).subject, "user-1");
      await verifier.verify(`Bearer ${token}`);
      assert.equal(discoveryHits, 1, "discovery runs once and is cached");
    } finally {
      await idp.close();
    }
  });

  test("a configured JWKS URL skips discovery", async () => {
    const key = await makeKey();
    const keys = await jwksServer([key]);
    try {
      const verifier = new TokenVerifier(testConfig({ jwksUrl: `${keys.url}/jwks` }));
      await verifier.verify(`Bearer ${await signToken(key)}`);
      assert.equal(keys.hits(), 1);
    } finally {
      await keys.close();
    }
  });

  test("a discovery document naming a different issuer gives 503", async () => {
    const idp = await listen((_req, res) => {
      res.writeHead(200, { "content-type": "application/json" });
      res.end(JSON.stringify({ issuer: "https://evil.example.invalid/", jwks_uri: "http://127.0.0.1:1/keys" }));
    });
    try {
      const verifier = new TokenVerifier(testConfig({ issuerUrl: `${idp.url}/` }));
      const e = await rejection(verifier.verify(`Bearer ${await signToken(await makeKey())}`));
      assert.equal(e.status, 503);
    } finally {
      await idp.close();
    }
  });

  test("failed discovery is retried on the next request", async () => {
    const key = await makeKey();
    let base = "";
    let up = false;
    const idp = await listen((req, res) => {
      if (!up) return void res.writeHead(500).end();
      res.writeHead(200, { "content-type": "application/json" });
      res.end(req.url?.endsWith("openid-configuration") ? JSON.stringify({ issuer: `${base}/`, jwks_uri: `${base}/keys` }) : JSON.stringify({ keys: [key.jwk] }));
    });
    base = idp.url;
    try {
      const issuer = `${base}/`;
      const verifier = new TokenVerifier(testConfig({ issuerUrl: issuer }));
      const token = await signToken(key, { iss: issuer });
      assert.equal((await rejection(verifier.verify(`Bearer ${token}`))).status, 503);
      up = true;
      assert.equal((await verifier.verify(`Bearer ${token}`)).subject, "user-1");
    } finally {
      await idp.close();
    }
  });
});
