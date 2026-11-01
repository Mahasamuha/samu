# Phase 4 implementation plan: principals, credentials, connectors

Status: draft for review, 2026-10-31. Builds on phase 3.5 (`docs/phase3.5-plan.md`): MCP `buildServer(principal)` builds a per-principal `capability://index`; upstream connector capabilities (Constellation) come from the accounts catalog; the MCP server's stale-while-revalidate cache of upstream `tools/list` is keyed by principal + connector (definitions only, never tokens).

## Context

`docs/phase4-plan.md` (drafted 2026-10-31, uncommitted) designs multi-person support: per-person identity, write-only stored credentials, grants for API keys, OAuth connectors, starting with Constellation. Nothing is built. This plan splits it into work packages that Sonnet implementors can each pick up cold, in dependency order, and records where the design needs adjusting first.

Decisions taken in this planning round:
- **Drizzle** for the accounts service (resolves §9 ORM question).
- **Full per-principal Anthropic keys**: own key → Bifrost provider key pinned to the person's VK; granted key → VK pinned to the owner's provider key.
- **No Bifrost MCP-gateway spike.** Build our own (rollout step 0 dropped; record why in `docs/phase4-notes.md`).

## Review: changes to make to phase4-plan.md before building

These come from reading the current gateway/MCP/app code against the plan. Fold them into `docs/phase4-plan.md` in the kickoff change (WP-K).

1. **Forward the JWT, drop `X-Samu-Principal`.** The MCP container reads untrusted content and will now be able to reach accounts. Make re-verification mandatory, not "recommended": the gateway forwards the phone's bearer to MCP and accounts; each verifies it (copy of `gateway/src/auth.ts`'s `TokenVerifier`). Principal = verified `sub`. No trusted-header path to strip or spoof. Plus a per-caller service token (`ACCOUNTS_GATEWAY_TOKEN`, `ACCOUNTS_MCP_TOKEN`): the MCP token is allowed only the internal resolve endpoint; the gateway token only control-plane and VK lookup.
2. **The gateway issues the callback redirect itself.** `forward()` (`gateway/src/proxy.ts`) passes only `content-type` from upstream, which is a non-negotiable. So `GET /connect/callback` is handled in the gateway: it calls accounts (JSON in, JSON out), then sends a 302 to a fixed app URI from its own config. No `location` from upstream, no redirect parameter.
3. **Distinct app redirect scheme.** AppAuth's `appAuthRedirectScheme` placeholder (`app/build.gradle.kts:80`) registers `com.mahasamuha.assistant:` for any path, so a post-connect redirect on that scheme would land in AppAuth's receiver. Use `com.mahasamuha.assistant.connect:/done` (custom scheme; no App Links, which would need another public route for `assetlinks.json`). Resolves §9.
4. **Anthropic resolution happens at provisioning time, not per call.** Bifrost pins a VK to `key_ids` (already done in `deploy/bifrost/config.example.json`). Setting/deleting an own key or creating/revoking a grant must re-point the affected VKs' `key_ids` synchronously, so "revocation takes effect on the next request" still holds for the LLM.
5. **Catalog lives in accounts only.** MCP stays catalog-free except a map of its built-in tools → connector id. Accounts' resolve response carries, for `oauth_mcp` connectors, the upstream URL, access token, tool allowlist, and per-tool `readOnlyHint` + confirmation template.
6. **Route table gaps.** `GET /grants` also returns the known principals (id + display name) for the grantee picker; no separate `/principals` route. Callback and CIMD doc are the only public additions.
7. **Session-start latency.** `RemoteTools` gives `tools/list` 4 s (`app/.../remote/RemoteTools.kt`). Upstream fan-out (resolve + upstream initialize + tools/list, maybe a token refresh) gets a per-upstream timeout (~2 s); a slow upstream's tools are left out of that session, not the whole list.
8. **Phone-side read-back templates aren't built yet** (plan §4b, "build with the first one"). Constellation writes are the first; WP-8 builds the `_meta` preprocessor.
9. **Display name.** Authentik's subject mode is hashed (`docs/authentik-setup.md`); whether the access token carries `name`/`preferred_username` is unverified (V2). Fallback: an accounts CLI command to set a principal's display name.
10. `docs/plan.md` uncommitted §6 text says service keys "can stay shared in `deploy/.env`"; phase4-plan supersedes that (all keys become credentials). Reconcile in WP-K.

## Target topology

```
networks:  llm     = gateway, bifrost, accounts      (accounts uses Bifrost admin API)
           tools   = gateway, mcp
           control = gateway, accounts
           resolve = mcp, accounts                    (MCP can reach accounts, never Bifrost)
           db      = accounts, postgres
```

Accounts is not on `tools`; MCP is not on `llm` or `db`.

## Verification tasks (before the dependent WP)

- **V1 — Bifrost admin API on v2.3.1** (blocks WP-2). On the workstation dev stack (`compose.build.yaml`), confirm: create an Anthropic provider key via API; create a VK with chosen `value`, `provider_configs[].key_ids`, `allowed_models`, budget; update a VK's `key_ids`; read VK spend. Also whether API-made changes survive restart when `config.json` is mounted read-only (config store in `bifrost-data`), and whether config-file entries get re-applied over them. Record in `docs/phase4-notes.md`. If VK `value` can't be chosen, accounts stores the generated one.
- **V2 — token claims** (blocks WP-2 display name). Decode an access token from the phone; note whether `name`/`preferred_username` is present. Record in phase4-notes.

## Work packages

Each WP is one Sonnet task, one commit (conventional prefix), typecheck clean. Follow CLAUDE.md: no infra specifics in tracked files, placeholders in `*.example`, no tests unless asked, comment density like the surrounding code.

### WP-K — Kickoff docs (me, not Sonnet)
- Commit the pending docs diff, apply review items 1–10 to `docs/phase4-plan.md`.
- `docs/plan.md`: decisions rows, §4 architecture, route allowlist, §5 renumbering (screen context → 5, gaps → 6), risks.
- `CLAUDE.md`: current phase, "one user", gateway state line, route allowlist, env list, repo layout (`accounts/`).
- New `docs/phase4-api.md`: the contract every WP codes against — public routes, internal accounts routes, JSON shapes, error codes, DB schema. Server and app WPs can then run in parallel.

### WP-1 — Accounts scaffold (after WP-K)
- `accounts/`: pnpm, TS strict ESM, Node ≥24, same `package.json`/`tsconfig`/`Dockerfile`/`.dockerignore` shape as `gateway/`. Deps: `drizzle-orm`, `pg`, `jose`; dev: `drizzle-kit`, `@types/pg`.
- `src/config.ts` modeled on `gateway/src/config.ts` (same `required`/`url`/`duration` helpers). Env: `DATABASE_URL`, `ACCOUNTS_MASTER_KEY` (base64 32 bytes), `ACCOUNTS_GATEWAY_TOKEN`, `ACCOUNTS_MCP_TOKEN`, `ISSUER_URL`, `JWKS_URL`, `AUDIENCE`, `BIFROST_ADMIN_URL`, `DEFAULT_BUDGET_USD`, `DEFAULT_BUDGET_RESET`, `PORT`.
- `src/auth.ts`: copy of the gateway's `TokenVerifier` (header comment: keep in sync with gateway and mcp copies) + caller check by service token (constant-time compare).
- `src/crypto.ts`: AES-256-GCM, random 96-bit nonce, AAD = `principalId|connectorId`, `key_version` column.
- `src/db/schema.ts` (Drizzle): `principals`, `credentials` (kind, ciphertext, nonce, key_version, owner, connector, status), `grants` (owner credential, grantee principal or wildcard), `oauth_pending` (state hash, principal, connector, PKCE verifier encrypted, expires_at, used_at), `virtual_keys` (principal, Bifrost VK id, encrypted value, pinned key id). Migrations generated by drizzle-kit, committed, applied at startup.
- `node:http` server like gateway `index.ts`: `HttpError` boundary, JSON logging, `/healthz`.
- `deploy/compose.yaml`: `accounts` + `postgres` (pinned tag, volume `accounts-db`), networks per topology, same hardening (`read_only`, `cap_drop`, `no-new-privileges`). `deploy/accounts.env.example`, README rows + backup note.
- `.github/workflows/images.yml`: add `accounts` to the matrix (`mahasamuha/samu-accounts`) with smoke env; healthz must not need the DB to be up (report db status separately or lazily connect).

### WP-2 — Principals + per-principal virtual keys (after WP-1, V1)
- Accounts: principal upsert on every verified request (display name from V2 result). `src/bifrost.ts` admin client (shapes from V1). `src/catalog.ts` with the five entries from phase4-plan §5 (fields per `api_key` connector, e.g. Plex: `token`, `url`, `tls_name`, `music_section`).
- Internal `GET /internal/virtual-key` (gateway token + user JWT): resolve the `anthropic` credential (own → grant → wildcard), ensure the VK exists and is pinned to that provider key with the default budget, return the VK value. None → 404 `no_model_access`.
- Seed CLI (`pnpm run seed:owner -- --sub <sub>`): creates the owner principal, imports the current Anthropic key as their credential and provider key, adopts the existing VK and budget. Run inside the container; values from env once, never logged.
- Gateway (`gateway/src/index.ts`, `config.ts`, `proxy.ts`): `/v1/responses` looks up VK via accounts with a 60 s in-memory cache keyed by sub; no VK → 403 `{error:{code:"no_model_access"}}`. Remove `UPSTREAM_VIRTUAL_KEY` (config, `gateway.env.example`, workflow smoke env). Add `ACCOUNTS_URL`, `ACCOUNTS_GATEWAY_TOKEN`.

### WP-3 — API-key credentials (after WP-2)
- Accounts: `PUT /credentials/{connector}` (validate fields against catalog; encrypt; for `anthropic` also create/replace the Bifrost provider key and re-pin the owner's VK), `DELETE /credentials/{connector}`, `GET /connectors` (status per principal: `own`, `shared` + owner name, `available`, `needs_reconnect`, `unavailable`). Write-only: no route returns a secret. Log principal/connector/action only.
- Internal `POST /internal/resolve` (MCP token + user JWT): body `{connectors:[...]}` → `{resolved:{id:{credentialId, secret|fields}}}`. Logs credential ID + principal per resolution.
- Gateway: generalize `ROUTES` from POST-only to method + path pattern (keep matching on the raw target, `index.ts:34`); add the control routes forwarding to accounts with the user bearer and gateway token; `forward()` takes a method; a second, stricter `RateLimiter` for credential/grant writes. Still 404 for anything unlisted.
- MCP (`mcp/src/index.ts`, `config.ts`): verify the forwarded JWT (copied verifier; add `jose`), `buildServer(principal)` calls resolve once per request and registers only tools whose connector resolved; `brave.ts`/`weather.ts`/`plex.ts` take the key/fields as a parameter instead of reading `config`. Gateway's `/mcp` route forwards the bearer.
- Migration: `seed:owner` extension imports `BRAVE_API_KEY`, `PIRATE_WEATHER_API_KEY`, `PLEX_*` from env. Removing them from compose/`.env.example`/README is a **separate follow-up commit** after the deployed import is verified.

### WP-4 — Grants (after WP-3)
- Accounts: `POST /grants` (owner + `api_key` credential only; schema has no FK path to OAuth rows), `DELETE /grants/{id}`, `GET /grants` (own grants + known principals). Wildcard grantee. Anthropic grants trigger VK re-pin (review item 4). Resolution order per phase4-plan §3 in one function used by both resolve and VK lookup.

### WP-5 — App: connections + grants screens (after WP-K contract; finishes after WP-4 deploy)
- New `app/.../accounts/AccountsClient.kt` (HttpURLConnection + `org.json`, like `remote/McpClient.kt`; bearer from `AuthManager.freshAccessToken()`, gateway from `AppConfig.gatewayUrls`).
- `ConnectionsScreen.kt` composable reached from `MainActivity`'s setup screen: row per connector with status; write-only paste fields (multi-field for Plex); "shared by <name>" + act-as-owner warning; grant screen with principal picker, the warning inside the confirm dialog.
- `llm/GatewayChatClient.kt` `errorFor`: 403 → new `ChatException.NoModelAccess`, spoken as "You don't have access to the assistant's language model yet."

### WP-6 — OAuth connect flow (after WP-3)
- Accounts: `POST /connect/{connector}/start` → discover via RFC 9728 → RFC 8414 (try the MCP SDK's client auth helpers in `@modelcontextprotocol/sdk/client/auth.js` before writing our own; check what 1.32.1 exports), PKCE, `resource` indicator, signed single-use `state` (~10 min, hashed in `oauth_pending`). Internal `POST /internal/connect/callback` exchanges the code and stores tokens. Refresh inside a transaction with `pg_advisory_xact_lock` per connection; refresh failure → status `needs_reconnect`. Never request Asterism's `admin` scope.
- Gateway: public `GET /connect/callback` (calls accounts, then 302 to `APP_CONNECT_REDIRECT` with only `?status=ok|error`); public CIMD `GET /.well-known/samu-client.json` built from `PUBLIC_BASE_URL` (client_id = its own URL, `redirect_uris` = the callback, `token_endpoint_auth_method: none`). Callback gets the write rate limiter.

### WP-7 — MCP upstream client + Constellation (after WP-6)
- Catalog entry `constellation` (`oauth_mcp`, upstream URL from env placeholder, allowlist and policies from phase4-plan §5; `delete`/`move`/`copy`/`list_hosts` excluded).
- MCP: for each resolved `oauth_mcp` connector, SDK `Client` + `StreamableHTTPClientTransport` with the bearer; `tools/list` filtered by allowlist, renamed `constellation__<tool>`, annotations and `_meta` confirmation template replaced from the catalog; `tools/call` forwarded. Per-upstream timeout (review item 7). Upstream 401 → ask accounts to mark `needs_reconnect`, tool result says "Your Constellation connection needs to be reconnected."
- All upstream tools marked as outside content (openWorldHint) so the phone's confirmation policy treats the session as having read outside content.

### WP-8 — App: OAuth connect + read-back templates (after WP-6 contract)
- Connect button → `start` → Custom Tab; manifest intent filter for `com.mahasamuha.assistant.connect:/done` on a small activity that returns to the connections screen and refreshes it.
- `McpClient.definition()` reads `_meta` confirmation template; `RemoteActionTool.prepare` fills it from actual arguments, appends unused arguments, and with no template asks with the spoken warning (plan §4b).

### WP-9 — Second person (user-driven)
- Identity provider binding, their sign-in, connections, a grant or two; a week of use. Notes in `docs/phase4-notes.md`.

## Order and parallelism

```
WP-K ─┬─ WP-1 ─ (V1) ─ WP-2 ─ WP-3 ─┬─ WP-4
      │                             └─ WP-6 ─ WP-7
      └─ WP-5 (UI against contract) · WP-8 (against contract)
                                                   └─ WP-9
```
Deploy and check on the home server after WP-2, WP-3 (then the env-key removal commit), WP-4, WP-7. Each deploy is usable on its own; WP-2 must leave the existing user's behavior unchanged.

## Verification

- Per WP: `pnpm typecheck` in each touched service; `./gradlew :app:assembleDebug` for app WPs; `docker compose -f compose.yaml -f compose.build.yaml up -d --build` on the workstation.
- WP-2: phone round trip unchanged; Bifrost dashboard shows spend on the adopted VK; a token for an unknown principal with no grant gets the spoken "no access" line.
- WP-3: `docker compose exec mcp env` shows no provider keys after the follow-up; search/weather/music still work; `GET /connectors` never contains a secret (grep response).
- WP-4: grant, use, revoke as the second principal; next request loses the tool / the LLM.
- WP-6/7: connect Constellation from the phone, "add milk to the shopping list" with read-back confirmation; revoke in Asterism → spoken reconnect message and row shows disconnected. Replay a used `state` → rejected.
- Before each commit: diff check for hosts, IPs, domains, real IDs (CLAUDE.md).
