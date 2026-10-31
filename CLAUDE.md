# Phone Assistant

The assistant is named **Samu**: the app's name and how it introduces itself. Bylines and any about text use "Samu by Mahasamuha".

Personal Android voice assistant, sideloaded onto one Pixel 8 (Android 17, API 37). It replaces Google Assistant for one user. A hosted LLM (Anthropic) sits behind a self-hosted gateway pair that handles spend tracking and caps.

The full design and its rationale are in `docs/plan.md`. Read it before making architectural choices. If something here conflicts with it, the plan wins. If the plan looks wrong, say so; don't silently diverge.

## Repo layout (target; create as needed)

```
app/       Android app — Kotlin, Jetpack Compose, Gradle (Kotlin DSL)
gateway/   Auth gateway — TypeScript on Node, pnpm
mcp/       MCP server for remote tools (web search) — TypeScript on Node, pnpm
deploy/    Server-side config: compose files for gateway + Bifrost, reverse proxy snippets, env templates (placeholders only)
docs/      plan.md and any design notes
skills/    SKILL.md instruction bundles (plan §4b); bundled into the app (delivery: app) or served as drafts (delivery: server)
capabilities/  capabilities.json: groups tools into capabilities (phase 3.5); bundled into the app (local, hybrid) and served by mcp/ (hybrid, server)
```

These are independent build units. Don't introduce a monorepo tool unless one is clearly needed.

## Architecture in one paragraph

The phone holds the default assistant role (`VoiceInteractionService`). A gesture opens a session panel. The phone does on-device STT, then sends an OpenAI Responses API request with an Authentik-issued bearer token to the public auth gateway. The gateway validates the JWT (JWKS signature, iss, aud, exp), rate-limits, attaches the phone's Bifrost virtual key, and streams the request to Bifrost. Bifrost is on a private network only; it enforces budget and model allowlist and talks to Anthropic. Tool calls come back to the phone and run there: contacts lookup, calls, SMS, and Open-Meteo weather as a fallback. Remote tools (web search via the Brave Search API, weather via Pirate Weather) are served by an MCP server on the same private network, reached through the gateway's `/mcp` route; the phone still runs the tool loop. The answer is spoken with on-device TTS.

## Non-negotiables

- No provider API keys or Bifrost keys on the phone. The app holds only issuer URL, client ID, and gateway base URL(s).
- Bifrost is never internet-reachable. Only the auth gateway is exposed.
- The gateway keeps its public surface minimal: an explicit allowlist of routes, each added deliberately and recorded in the plan, all requiring a valid token except the health check. Everything else returns 404. Today the allowlist is `POST /v1/responses`, `POST /mcp`, and `GET /healthz`.
- The gateway streams responses through without buffering (SSE pass-through) and forwards only `content-type` from upstream. Bifrost must keep `extra_fields_placement: "headers"`, or its response bodies leak provider account IDs (`docs/phase2-notes.md`).
- Side effects follow the confirmation policy in plan §2: SMS always confirmed; calls announced, and confirmed when the contact match is ambiguous or the session has read outside content (search, weather).
- STT, TTS, and the LLM endpoint each sit behind an interface so implementations can be swapped.
- Endpoints are configured as a list, even though v1 uses one.
- v1 calls the LLM only while the phone is unlocked.

## Infrastructure specifics stay out of the repo

No infrastructure specifics go into tracked files or commit messages. This covers host names, IP addresses, domains and subdomains, ports specific to the real deployment, network names, real issuer/client IDs, virtual-key names, and file paths on real servers.

- In docs, code comments, and examples, use generic terms ("the home server", "the identity provider") or placeholders (`<gateway-host>`, `<issuer-url>`, `<client-id>`).
- Real values live only in untracked files: `.env` files, `app/local.properties` (or a gitignored `app/config.local.properties` read by Gradle), and `deploy/local/`. Commit `.env.example` / `*.example` files with placeholders.
- Keep `.gitignore` covering `.env`, `.env.*` (except `.env.example`), `local.properties`, `*.local.*`, `deploy/local/`, and keystores (`*.jks`, `*.keystore`).
- Before committing, check the diff for anything that looks like a real host, IP, or domain. If unsure whether something counts, treat it as specific and ask.
- This applies to `docs/` too, including `docs/phase1-notes.md` and any updates to `docs/plan.md`.

## Conventions

**General**
- Readability over cleverness.
- Throw exceptions and handle them at the boundary.
- Never hardcode secrets. Use env vars, and commit `.env.example` files, never `.env`.
- Don't write tests unless asked.
- Commit messages use conventional prefixes (`feat:`, `fix:`, `chore:`).

**TypeScript (gateway)**
- Strict mode, ESM, async/await, pnpm.
- Keep dependencies minimal. Suggested: `jose` for JWT/JWKS (`createRemoteJWKSet` + `jwtVerify`), and Node's built-in `fetch` or `undici` for upstream streaming. Pick a small HTTP layer (plain `node:http` or Hono); no Express middleware sprawl.
- State is limited to the in-memory rate limiter and the debug-logging toggle. Both may reset on restart.
- Config comes entirely from env: `ISSUER_URL`, `JWKS_URL` (optional, discovered from the issuer if absent), `AUDIENCE`, `UPSTREAM_URL`, `UPSTREAM_VIRTUAL_KEY`, `RATE_LIMIT_*`, `LOG_BODY_RETENTION`, `PORT`.

**Kotlin (app)**
- Compose for UI and coroutines for async. Don't introduce RxJava.
- `minSdk`/`targetSdk`/`compileSdk` = 37. Only one device exists, so there's no reason to support older APIs.
- Use AppAuth-Android for OAuth (auth code + PKCE via custom tab).
- Store the refresh token encrypted with a hardware-backed Android Keystore key.
- Keep dependencies lean. Ask before adding a DI framework; manual construction is fine at this size.

## Android 17 gotchas (details in plan §3)

- **Audio while not visible fails silently** for audio played in our own process. Platform `TextToSpeech` is not affected: the engine plays in its own process, and on-device testing showed speech continuing after the panel closed with no foreground service (`docs/phase1-notes.md`). Recheck if we ever play audio ourselves (e.g. cloud TTS through `AudioTrack`).
- **Background activity launches are restricted.** Fire `ACTION_CALL` and other activity launches while the session is visible.
- **Contacts:** some account columns moved off `ContactsContract.Data`. Read them from `RawContacts`.

## Current phase

Phases 1–3 are done (`docs/phase1-notes.md`, `docs/phase2-notes.md`, `docs/phase3-notes.md`): invocation, the gateway + Bifrost voice round trip, and the tools-and-skills design in plan §4b, including the MCP server (web search, Pirate Weather). Two security review rounds have been fixed and checked on device. **The server side is deployed on the home server**: the gateway and MCP images are published to Docker Hub by `.github/workflows/images.yml` and pulled there (`deploy/README.md`); the dev stack on the workstation is stopped. **In progress: phase 3.5, capabilities and late binding** (`docs/phase3.5-plan.md`): a capability manifest in `capabilities/`, a `capability://index` MCP resource cached on the phone, and eager/lazy binding through `use_capability`. Then phase 4, principals, credentials and connectors (`docs/phase4-plan.md`); screen context moved to phase 5. Draft skills reach the phone through the capability index once a draft exists. The phase 1 and phase 2 sections below are kept as the original briefs.

### Phase 1 — Prove invocation

Goal: the app can be selected as the default digital assistant on the Pixel 8, and a power-button long-press opens our session panel.

1. Scaffold `app/`: single module, Compose, API 37, package name TBD (ask; it gets tied to the signing key for developer verification).
2. Implement the minimum the system needs to list the app as an assistant:
   - A `VoiceInteractionService` subclass, declared with `android.permission.BIND_VOICE_INTERACTION` and the `android.service.voice.VoiceInteractionService` intent filter.
   - A `VoiceInteractionSessionService` that returns a `VoiceInteractionSession`.
   - The `android.voice_interaction` meta-data XML (`sessionService`, `recognitionService`, `supportsAssist="true"`, `supportsLaunchVoiceAssistFromKeyguard` as needed).
   - A stub `RecognitionService`. Uncertain: historically the settings picker would not list an assistant without one. Include it and verify.
3. The session shows a Compose panel saying "Listening…" with a close button, and logs `onShow` / `onHandleAssist` calls so we can see what context arrives.
4. Add a small launcher activity that explains setup and deep-links to the default-assistant settings screen. Uncertain: `ROLE_ASSISTANT` cannot be requested via `RoleManager.createRequestRoleIntent`, so the user has to select it in Settings. Verify.
5. Verify on the device and record the results in `docs/phase1-notes.md`:
   - The app appears in Settings → Apps → Default apps → Digital assistant app.
   - Power-button long-press opens the panel (Settings → System → Gestures → Press & hold power button → Digital assistant).
   - Corner swipe and headset button behavior.
   - Whether the panel counts as "visible" for audio. Play a short TTS line from `onShow`.

Install via `adb` or Android Studio. ADB installs are exempt from developer verification.

### Phase 2 (server half) — preview, don't start unprompted

- `gateway/`: JWT validation, route allowlist, rate limit, virtual-key injection, SSE pass-through, debug-logging toggle with a ~30-minute auto-off.
- (Added in phase 3) `mcp/`: the MCP server for remote tools (plan §4b step 4), stateless, plain-JSON Streamable HTTP, reached only through the gateway.
- `deploy/`: compose for gateway + Bifrost on a private network (real values in gitignored `deploy/local/` or `.env`), Bifrost config with a budgeted virtual key restricted to a small Anthropic model, and a reverse-proxy route to the gateway only.
- Authentik: a public client with PKCE and a dedicated audience. This is manual setup in Authentik; document the steps rather than automating them.

## Working agreements

- When uncertain about an Android API's behavior on API 37, say so and propose an on-device check. Don't assert from older-version knowledge.
- Ask one question when a missing answer changes the approach. Otherwise state the default and proceed.
- Keep `docs/plan.md` current. If a decision changes, update the decisions table in the same change.
