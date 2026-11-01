import {
  createRemoteJWKSet,
  errors,
  jwtVerify,
  type JWTPayload,
  type JWTVerifyGetKey,
} from "jose";
import type { Config } from "./config.js";
import { HttpError } from "./errors.js";

export interface Identity {
  subject: string;
}

// Asymmetric algorithms only; anything else in a token header is rejected.
const ALGORITHMS = [
  "RS256",
  "RS384",
  "RS512",
  "PS256",
  "ES256",
  "ES384",
  "EdDSA",
];

/**
 * Validates bearer tokens against the identity provider's JWKS: signature, iss, exp/nbf, and aud as one exact string.
 * The JWKS URL is discovered lazily so the gateway can start while the identity provider is down.
 */
export class TokenVerifier {
  private jwks: Promise<JWTVerifyGetKey> | undefined;

  constructor(private readonly config: Config) {}

  async verify(authorization: string | undefined): Promise<Identity> {
    const token = /^Bearer\s+(\S+)$/i.exec(authorization ?? "")?.[1];
    if (!token)
      throw new HttpError(401, "Missing bearer token", {
        "www-authenticate": "Bearer",
      });

    const jwks = await this.getJwks();
    let payload: JWTPayload;
    try {
      // `audience` is deliberately not passed: jose accepts an aud ARRAY that merely contains the
      // expected value. aud is checked below as a single exact string instead.
      ({ payload } = await jwtVerify(token, jwks, {
        issuer: this.config.issuerUrl,
        algorithms: ALGORITHMS,
        clockTolerance: 30,
        requiredClaims: ["exp", "sub"],
      }));
    } catch (e) {
      if (isIdentityProviderFailure(e))
        throw new HttpError(503, "Identity provider unavailable");
      if (e instanceof errors.JOSEError) throw invalidToken();
      throw e;
    }

    if (typeof payload.aud !== "string" || payload.aud !== this.config.audience)
      throw invalidToken();
    return { subject: payload.sub as string };
  }

  private getJwks(): Promise<JWTVerifyGetKey> {
    this.jwks ??= this.resolveJwksUrl()
      .then((jwksUrl) => createRemoteJWKSet(new URL(jwksUrl)))
      .catch((e: unknown) => {
        this.jwks = undefined; // retry discovery on the next request
        console.error(
          JSON.stringify({ event: "jwks_discovery_failed", error: String(e) }),
        );
        throw new HttpError(503, "Identity provider unavailable");
      });
    return this.jwks;
  }

  private async resolveJwksUrl(): Promise<string> {
    if (this.config.jwksUrl) return this.config.jwksUrl;
    const issuer = this.config.issuerUrl;
    const discoveryUrl = `${issuer.endsWith("/") ? issuer : `${issuer}/`}.well-known/openid-configuration`;
    const response = await fetch(discoveryUrl, {
      signal: AbortSignal.timeout(5_000),
    });
    if (!response.ok) throw new Error(`discovery returned ${response.status}`);
    const document = (await response.json()) as {
      issuer?: unknown;
      jwks_uri?: unknown;
    };
    if (document.issuer !== issuer)
      throw new Error(
        `discovery issuer ${String(document.issuer)} does not match ISSUER_URL`,
      );
    if (typeof document.jwks_uri !== "string")
      throw new Error("discovery document has no jwks_uri");
    return document.jwks_uri;
  }
}

/**
 * True when verification failed because the key set could not be fetched, not because of the token.
 * - JWKSTimeout / JWKSInvalid: jose's own classes for a slow or malformed key set.
 * - A bare JOSEError (code ERR_JOSE_GENERIC): jose throws this class directly only for a non-200 or non-JSON
 *   key set response (jwks/remote.js); every token problem uses a more specific subclass.
 * - A TypeError carrying a `cause`: how fetch reports a network failure (refused, DNS, reset). jose's own
 *   TypeErrors (bad options) have no cause, so programming errors still surface as 500.
 */
function isIdentityProviderFailure(e: unknown): boolean {
  if (e instanceof errors.JWKSTimeout || e instanceof errors.JWKSInvalid)
    return true;
  if (e instanceof errors.JOSEError) return e.code === "ERR_JOSE_GENERIC";
  return e instanceof TypeError && e.cause != null;
}

function invalidToken(): HttpError {
  return new HttpError(401, "Invalid token", {
    "www-authenticate": 'Bearer error="invalid_token"',
  });
}
