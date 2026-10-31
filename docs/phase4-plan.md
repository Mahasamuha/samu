# Phase 4 — Principals, credentials, and connectors (plan)

Status: planned 2026-10-31, nothing built. This becomes phase 4; screen context moves to phase 5 and the old phase 5 (gaps) to phase 6. Update `docs/plan.md` §5 and the CLAUDE.md "Current phase" section in the same change that starts this work.

## 1. Goal

Let a second person use and configure Samu on their own phone without full multi-user product work:

- Each person can bring their own keys (Anthropic, Pirate Weather, Brave, Plex, and so on) and connect their own accounts on predefined MCP services.
- The owner of a key can share it with another person. Shared keys stay on the server; the grantee never sees them.
- Tools a person can't resolve a credential for simply don't appear in their session.

Non-goals for this phase:

- Self-service sign-up. Who can use Samu is still decided in the identity provider (application binding to a user or group).
- Arbitrary MCP servers. Only connectors in the catalog (§5) can be connected.
- Sharing OAuth connections, in any form (§2).
- Admin UI beyond what the app needs.

## 2. Decisions

| Decision | Rationale |
|---|---|
| Identity = identity-provider `sub` | The gateway already verifies it on every request and rate-limits by it. A principal row is created on first authenticated request; no separate sign-up. |
| Secrets never reach a phone | Unchanged non-negotiable. Keys are entered on the phone and sent once, write-only; OAuth tokens for upstream services are held only on the server. |
| API-key credentials are shareable | Grant = (grantee, connector, credential). Only the credential's owner can create or revoke one. |
| OAuth connections are **never** shareable | The data model has no grant path for them, not a hidden one. A connection is a person's account on another service. |
| Grant screens warn about acting as the owner | Shown on every grant and on the grantee's view of a shared connector: the grantee acts on behalf of and as the owner (usage billed and logged to the owner; for account-scoped keys such as Plex, they browse the owner's account). |
| Credentials are write-only through every API | Set, replace, delete, and status. Never read back. |
| Connector catalog lives in code | Predefined connectors only; a catalog entry is a reviewed code change, not a database row. |
| Control plane separate from tool execution | The MCP container reads untrusted web content and stays isolated (no master key, no database, no Bifrost admin access). A new **accounts** service owns secrets. |
| Bifrost: one virtual key per principal | Budgets and cost tracking per person. The Anthropic key behind it is a credential like any other: the principal's own, or one granted to them. |
| Constellation is the first OAuth MCP connector | It sits behind Asterism, so the full OAuth path (start, callback, token storage, refresh) is exercised against a server we control before any third-party one. |

## 3. Concepts

- **Principal:** a person, keyed on the identity provider's `sub`. Holds a display name (from the token) for grant screens.
- **Credential:** a secret owned by exactly one principal, for one provider. Kinds: `api_key` (pasted, shareable) and `oauth_connection` (access + refresh token for one upstream MCP server, never shareable).
- **Grant:** lets a grantee use one connector through one of the owner's `api_key` credentials. A wildcard grantee ("everyone who can sign in") is allowed.
- **Connector:** a catalog entry: what the provider is, how it authenticates, which tools it exposes, and each tool's confirmation policy.

### Credential resolution

For a principal and a connector, fixed order:

1. The principal's own credential.
2. An explicit grant to the principal.
3. A wildcard grant.
4. None: the connector's tools are left out of the session.

Every tool call and every LLM request logs the credential ID used (never the secret) with the principal. That is the audit trail and the per-person cost record for providers Bifrost doesn't track.

## 4. Architecture

```
Phone ── HTTPS + bearer ──▶ Reverse proxy ──▶ Auth gateway
                                                 │  verifies JWT, sets X-Samu-Principal (strips any inbound copy)
                         ┌───────────────────────┼─────────────────────────┐
                         ▼                       ▼                         ▼
                   Bifrost (llm net)       MCP service (tools net)   Accounts service (new)
                   VK per principal        resolves credentials      DB + master key; credentials,
                                           per call from accounts;   grants, OAuth connect + refresh,
                                           MCP client to upstream    Bifrost provisioning
                                           connectors                       │
                                                                            ▼
                                                               Upstream authorization servers
                                                               (Asterism for our own MCP servers)
```

### Auth gateway

- After verifying the JWT, set the principal on every upstream request. Strip any inbound header with that name first.
- `/v1/responses`: look up the principal's virtual key from accounts (short in-memory cache, e.g. 60 s) instead of the static `UPSTREAM_VIRTUAL_KEY`. No virtual key means 403 with a clear error the app can speak.
- Defense in depth (recommended): forward the verified JWT to accounts and the MCP service too and let them re-verify, rather than trusting the header alone. The accounts service is the one that matters most here.
- New routes, each added to the allowlist and to `docs/plan.md` deliberately:

| Route | Auth | Purpose |
|---|---|---|
| `GET /connectors` | bearer | Catalog with per-principal status: connected (own), shared to you (with owner), available, not available |
| `PUT /credentials/{connector}` | bearer | Set or replace an API key. Body is the key; response is status only |
| `DELETE /credentials/{connector}` | bearer | Remove own key or OAuth connection |
| `POST /grants` / `DELETE /grants/{id}` / `GET /grants` | bearer | Owner manages grants on own API-key credentials |
| `POST /connect/{connector}/start` | bearer | Returns the upstream authorization URL with a signed, short-lived, single-use `state` |
| `GET /connect/callback` | **public** | Upstream redirect target. Protected by `state` + PKCE. Exchanges the code, stores tokens, then redirects to the app |
| `GET /.well-known/samu-client.json` (name TBD) | public | CIMD client metadata document for Samu as an OAuth client to upstream servers |

### Accounts service (new)

- TypeScript on Node, strict, pnpm, like the gateway and MCP service. Private network only; reached from the gateway (for the routes above) and from the MCP service (credential resolution).
- Postgres. Persistent volume; backed up with the rest of the server.
- Owns: principals, credentials, grants, OAuth connections, pending OAuth flows, principal → Bifrost virtual key mapping.
- On the `llm` network as well, for Bifrost's admin API (provisioning virtual keys and provider keys). It is not internet-facing directly, which is why this is acceptable here and not in the gateway.
- Internal API for the MCP service: `resolve(principal, connector)` returns the secret or a fresh access token for that one call. The MCP service caches nothing past the request.
- Token refresh: serialized per connection (row lock or advisory lock), because refresh tokens rotate and a race loses the connection.

### Encryption at rest

- One master key from env (`ACCOUNTS_MASTER_KEY`), never in the database or image.
- AES-256-GCM per secret, random 96-bit nonce, the row's (principal, connector) as associated data so a ciphertext can't be moved to another row.
- Key version stored per row, so the master key can be rotated by re-encrypting.
- No KMS or Vault. Overkill for this deployment.

### MCP service

- `buildServer()` becomes `buildServer(principal)`. Tools register only when accounts resolves a credential for their connector, so availability per person falls out of resolution.
- Existing tools (`web_search`, `get_weather`, `music_find`) read their key from accounts instead of env. Env keys become a migration path only (§8 step 2), then go away.
- New: an MCP client to upstream connectors. For each connected upstream, it fetches `tools/list`, keeps the catalog's allowlisted tools, namespaces them (`constellation__read_file`), and forwards `tools/call` with the principal's token for that upstream.
- Upstream tool annotations are not trusted for confirmation. The catalog sets `readOnlyHint` and the confirmation template (`_meta`, plan §4b) on the tools it re-exposes.
- Stays stateless; the per-request upstream connection can be pooled later if latency calls for it (measure first).

### Bifrost

- One virtual key per principal, each with its own budget. New principals get a default budget from accounts' config.
- A principal's own Anthropic key: registered in Bifrost as a provider key and attached to their virtual key.
- A principal using a granted Anthropic key: their virtual key routes to the owner's provider key; budget and cost stay on the grantee's virtual key.
- **Verify before building:** whether OSS virtual keys can be pinned to specific provider keys. If not, fall back to one Bifrost provider entry per credential, or decide per-person Anthropic keys aren't worth it yet.

## 5. Connector catalog

Each entry:

- `id`, display name, provider.
- `auth`: `none`, `api_key`, or `oauth_mcp` (upstream MCP URL; authorization server discovered per the MCP spec, RFC 9728 protected-resource metadata).
- For `oauth_mcp`: scopes to request; tool allowlist; per tool, `readOnlyHint` and a confirmation template.
- Shareable: derived from `auth` (only `api_key`), not configurable.

Initial entries:

| Connector | Auth | Notes |
|---|---|---|
| Anthropic (LLM) | `api_key` | Via Bifrost, not the MCP service |
| Brave Search | `api_key` | `web_search` |
| Pirate Weather | `api_key` | `get_weather` (phone's Open-Meteo stays the fallback) |
| Plex | `api_key` (token + server URL) | `music_find`. The grant warning matters most here: it's an account credential |
| Constellation | `oauth_mcp` via Asterism | First OAuth connector, below |

### Constellation (first OAuth connector)

- **Client identity:** Samu registers with Asterism through CIMD (the metadata document route above). Asterism already accepts CIMD clients; no DCR.
- **Consent:** each person connects once from the app; the browser goes through Asterism and the identity provider. Refresh is silent after that. Asterism's short-lived `admin` scope is never requested.
- **Tools exposed:**
  - Read-only, no confirmation: `list_shares`, `list_directory`, `read_file`, `find_files`, `grep_files`, `file_info`.
  - Writes, always confirmed with path and action read back: `write_file`, `edit_file`, `create_directory`.
  - Left out of the catalog: `delete`, `move`, `copy`. A misheard path on a destructive operation is a bad trade; add them only for a concrete voice use case.
- **Scope of access:** whatever the person's own Constellation access is. A Constellation-side per-client share allowlist (feature request filed with Constellation) would let Samu be restricted to, for example, a notes share. Until then, the tool allowlist is the only restriction.
- **Use case to test the model's handling:** "add milk to the shopping list" / "add a note that…" against a single notes file. Haiku may struggle with read-modify-write of a structured file; if so, a purpose-built `append_to_list` tool in Samu's MCP service (calling Constellation underneath) is the fallback rather than a bigger model.
- `list_hosts`: include only if share disambiguation needs it in practice.

## 6. App changes

- **Connections screen** (setup/options screen, plan §5b): one row per catalog connector with status.
  - API-key connectors: paste field, write-only; shows "set" or "shared by <name>", never the value.
  - OAuth connectors: Connect / Disconnect. Connect calls `/connect/{connector}/start` and opens the URL in a custom tab; the callback ends with a redirect to an app link that refreshes the screen.
  - Shared connectors show the act-as-owner warning on the grantee side too.
- **Grant screen** on each of the person's own API-key credentials: add or remove grantees from the list of known principals. The warning is shown before a grant is confirmed and is part of the confirm dialog, not a footnote.
- **Session:** unchanged. The tool list still comes from `tools/list`; a person without a credential for a connector just doesn't get its tools, and skills that need them drop out (plan §4b).
- Spoken errors: "You don't have access to the assistant's language model yet" for a missing virtual key; "Your <connector> connection needs to be reconnected" when a refresh fails (and the app shows the row as disconnected).

## 7. Security notes

- `GET /connect/callback` is the only new unauthenticated route that does work. `state` is signed, bound to the principal and connector, single-use, and expires in ~10 minutes; PKCE verifier stored server-side with it.
- Redirect after callback goes only to the app's own registered link. No open redirect parameter.
- Credential write routes are rate-limited separately and logged (principal, connector, action), never with the value.
- Grantee lists are visible only to the credential's owner. A grantee sees the owner's display name and nothing else about the credential.
- Revoking a grant takes effect on the grantee's next request (resolution is per call).
- Upstream MCP output is untrusted content: it counts as outside content for the confirmation policy (plan §2), same as search and weather.

## 8. Rollout

Each step is usable on its own.

0. **Spike: Bifrost's MCP gateway** (one afternoon, before step 3). Bifrost now documents per-user OAuth and per-user headers for upstream MCP servers, keyed by virtual key in the non-enterprise mode. If it works on our version, it could replace much of steps 3–5. Plan §4b rejected Bifrost's MCP integration because it would run the tool loop in the LLM gateway and tie tools to Bifrost. Using only its MCP gateway endpoint as an aggregator keeps the loop on the phone, but still ties connectors to Bifrost and puts untrusted upstream content in the same container as Bifrost's admin API. Default: build our own unless the spike shows a large saving; record the outcome in `docs/phase4-notes.md`.
1. **Principals + per-principal virtual keys.** Accounts service, database, gateway lookup of virtual key by principal. Seed the existing user's principal with today's virtual key. No behavior change for the existing user.
2. **API-key credentials.** Encryption, write-only routes, MCP service resolving keys from accounts. Migrate today's env keys into the existing user's credentials, then remove them from env.
3. **Grants.** Grant routes, resolution order, wildcard grants, app connections and grant screens with the warning.
4. **OAuth connect flow + Constellation.** CIMD document, connect start/callback, token storage and serialized refresh, MCP client to Constellation through Asterism, catalog allowlist and confirmation templates.
5. **Second person.** Identity provider access for them, their own connections, a grant or two; a week of real use.

Exit criterion as for other phases: used daily for a week by both people without reaching for a workaround.

## 9. Open questions

- ORM for the accounts service (Prisma, as Asterism uses, vs Drizzle vs plain `pg`). Decide when starting step 1.
- Bifrost: virtual key pinned to specific provider keys in OSS (§4), and whether the deployed version has the per-user MCP auth features (§8 step 0).
- Whether per-principal Anthropic keys are worth it at all, versus everyone using a granted key with their own virtual-key budget.
- App link vs custom scheme for the post-callback redirect.
- Whether the second person needs anything beyond the identity provider's existing sign-in (local accounts in Asterism exist but aren't needed if they're in the identity provider).

## 10. Docs to update when this starts

- `docs/plan.md`: decisions table (rows from §2), §4 architecture, gateway route allowlist, §5 phase list renumbering, risks (stored secrets, public callback route).
- `CLAUDE.md`: "one user", the gateway's "state is limited to…" line, the route allowlist, the env var list (`UPSTREAM_VIRTUAL_KEY` goes away), repo layout (`accounts/`), current phase.
- `deploy/`: accounts service and Postgres in compose; env templates with placeholders only.
