# Phase 2 — Server notes

## Spike: web search through Bifrost (2026-10-20)

Goal: resolve plan §6, whether Anthropic's server-side web search survives Bifrost's OpenAI-compatible route, and whether Bifrost's governance covers it.

Setup: Bifrost `v2.3.1` in Docker on a workstation, bound to localhost, Anthropic key from `deploy/local/.env`. Model `claude-haiku-5-5`, tool `web_search_20250305` (`max_uses` 1–2). Question: "What is the weather in Boston right now?" Total spend for the spike: about 6¢.

### Results

| Check | Result |
|---|---|
| Haiku 5.5 supports web search | Yes. Models API: `capabilities.server_tools.web_search.supported = true`; code execution and `thinking: disabled` also supported. |
| Web search on `POST /v1/chat/completions` | Works. Pass the Anthropic tool object as-is in `tools` (`{"type": "web_search_20250305", "name": "web_search", "max_uses": N}`). The search runs; the reply is plain `message.content`. Usage reports `completion_tokens_details.num_search_queries` and `usage.tool_usage.web_search.num_requests`. Citations and search-result blocks are not returned on this route (fine for voice). |
| Streaming (SSE) | Works with web search. |
| Turning thinking off | `"reasoning_effort": "none"` (the standard OpenAI field) works; Anthropic-style `"thinking": {"type": "disabled"}` also works. Use `reasoning_effort` to stay provider-neutral. |
| Cost tracking | Bifrost's logged cost includes the $0.01 search fee (e.g. 15,524 input × $0.10/M + 158 output × $0.50/M + $0.01 = $0.0116314, exactly as logged). The breakdown labels it all `input_cost`. |
| Virtual key required | With `client.enforce_auth_on_inference: true`: no key → 401; unknown key → 401. |
| Model allowlist | A model outside `allowed_models` → 403. |
| Budget | Enforced before each request, including search fees: with a $0.015 budget, requests 1–2 ran and request 3 got 402 (`Budget exceeded: 0.0233 >= 0.0150`). Overshoot is at most one request. Usage persists across a container restart. |

### Latency (streamed, one search, workstation → Anthropic)

| Variant | First text | Total |
|---|---|---|
| Default thinking (adaptive, ~370 thinking tokens) | 4.9 s | 5.4 s |
| `reasoning_effort: "none"` | 1.8 s | 4.0 s |

With thinking off, the model usually streams a short preamble ("I'll check the current weather in Boston for you.") before the search, then the answer. The stream doesn't mark the boundary, and the two sentences arrive with no space between them ("…for you.Based on…"). The app should speak the preamble while waiting and split sentences without relying on a following space.

### Things the gateway and deploy must handle

- **`extra_fields`.** By default Bifrost adds `extra_fields` to every response, every SSE chunk, and error bodies, including `provider_response_headers` with the Anthropic organization and workspace IDs. Fixed in config: `client.extra_fields_placement: "headers"` removes it from bodies and chunks (nested fields are dropped entirely; scalars move to `x-bifrost-*` headers, which the gateway doesn't forward). Verified end to end: 0 of 35 stream chunks contained it.
- **Bifrost logs content by default** (`content_summary` with the prompt and answer). Turned off with `client.disable_content_logging: true`; body logging with retention belongs to the gateway (plan §2, Logging).
- **The "telemetry" plugin** is Bifrost's local Prometheus metrics, not phoning home. Left as is.
- **Governance needs the config store.** With `config_store.enabled: false`, the governance plugin fails to start. Use the default SQLite config store (on a persistent volume, which also keeps budget usage across restarts).
- **Virtual keys must name provider keys by ID.** Give each provider key an explicit `id` and list it in the virtual key's `provider_configs[].key_ids`. `key_ids` doesn't accept key names (fatal at startup), and omitting it leaves the virtual key with no usable key (`no_key_supports_model`).
- **Virtual key header:** `x-bf-vk`. Keys prefixed `sk-bf-` are also accepted in `Authorization`, but the gateway should send `x-bf-vk`.
- **Unverified:** whether editing `config.json` (e.g. raising the budget) resets stored usage; whether an unclean shutdown loses recent usage.

### Prompt notes for later

- Without guidance the model hedges ("conditions may vary", "check a live source"). The system prompt should ask for a single, most recent reading and no caveats.

## End-to-end test of gateway + Bifrost (2026-10-20)

`deploy/compose.yaml` run locally with test values, plus a mock identity provider container on the backend network issuing Authentik-style tokens (issuer `.../application/o/<slug>/`, RS256, JWKS via discovery). About 4¢ spent.

| Check | Result |
|---|---|
| Route allowlist | `GET /healthz` 200; `/`, `POST /v1/models`, `GET /v1/chat/completions`, `/api/logs`, `POST /anthropic/v1/messages` all 404 |
| Token validation | Missing, garbage, expired, wrong `aud`, wrong `iss`, unpublished signing key, `alg: none`, missing `sub`: all 401. Valid token passes. Identity provider unreachable: 503. |
| Rate limit (20/min, burst 5) | 6th rapid request 429 with `retry-after: 3` |
| Body checks | 300 KB body 413; non-JSON 400; JSON array 400 |
| Streamed web search | 200, `text/event-stream`; response headers are only `content-type`, `cache-control`, `x-accel-buffering` (plus Node's own); no `extra_fields` in any chunk |
| Non-streamed | Standard OpenAI body, no `extra_fields` |
| Client hangs up mid-stream | Logged as 499, upstream request cancelled |
| Debug toggle | `SIGUSR2` on (auto-off time logged), `SIGUSR2` off |
| Body log | Hourly JSONL, mode 600, request + assembled answer + usage; raw events when debug is on |
| Budget ($0.03 test cap) | Refused with 402 `budget_exceeded` at $0.0346; the phone gets the message to speak |

Test-harness note: the host firewall blocks containers from reaching services on the host, so the mock identity provider has to run as a container on the compose network.

## Dev deployment through the reverse proxy (2026-10-20)

Stack running on the development workstation (`GATEWAY_BIND` on its LAN address), Nginx Proxy Manager on the home server forwarding the dev hostname to it, real Authentik issuer and audience in `deploy/local/gateway.env`, $10/month budget.

- Discovery against the real issuer: issuer string matches exactly (trailing slash included), RS256 key in the JWKS, scopes `openid` and `offline_access`, PKCE `S256` supported.
- Authentik's token preview: `aud` is the client ID as a single string; `sub` is the hashed user ID. (The preview's `exp` doesn't reflect the access token validity setting.)
- From outside: Let's Encrypt certificate valid; HTTP redirects to HTTPS; `/healthz` 200; unauthenticated POST 401; other paths 404.
- Gotchas: Let's Encrypt fails until the hostname has its own DNS record (no wildcard record on this domain). After adding it, a machine that looked the name up earlier keeps the negative answer cached for a while (`resolvectl flush-caches` clears it).

- First request with a real Authentik-issued token (app sign-in, Pixel 6a): 200 through the proxy, gateway and Bifrost, 1.6 s, `sub` matching the hashed ID from the preview.
- A local DNS filter on the LAN cached the "no such name" answer from before the record existed, even longer than the workstation did; testing on mobile data worked. Flush its cache after adding a hostname.
- Scanners found the new hostname within minutes (certificate transparency): dozens of `GET /` and probes like `POST /api/graphql`, all 404 from the gateway.

## App sign-in (2026-10-20)

AppAuth 0.11.1, authorization code + PKCE (S256) in a Custom Tab, scopes `openid offline_access`, redirect `com.mahasamuha.assistant:/oauth2redirect`. AppAuth's merged manifest brings the browser `<queries>` and the redirect receiver, so no extra manifest work on API 37.

- Auth state (with the refresh token) is AES-256-GCM encrypted under an Android Keystore key generated with StrongBox when available, else TEE, and `setUnlockedDeviceRequired(true)`, so it can't be decrypted while the phone is locked.
- The issuer and client ID come from the gitignored `app/config.local.properties` into `BuildConfig` (`config.example.properties` is the template). The gateway URL was a comma-separated list there in phase 2; it is now set on the setup screen (plan §5b).
- The setup screen's "Test gateway" button sends one non-streamed request with a fresh token (temporary; replaced by the streaming client).


## Voice round trip (2026-10-20, Pixel 6a)

STT (`PlatformSpeechToText`, on-device `SpeechRecognizer`) → `GatewayChatClient` (streaming, web search, `reasoning_effort: "none"`) → `SentenceSplitter` → `PlatformSpeaker` (TTS with ducking audio focus). Each behind an interface; `VoiceSessionController` runs the turns and keeps history for the session.

- On-device recognition is available on the 6a and works. Holding the assistant role didn't change the default recognizer (`voice_recognition_service` stays Google's), so our stub isn't used for STT.
- Microphone "while using the app" is enough for gesture invocations: the panel is in use, and the role holder's process has the microphone capability.
- Turn latency through the full stack: about 2 s without search, 3–3.5 s with one search.
- With thinking off, the model sometimes narrates its reasoning in the answer ("The user's location isn't known, so I need to ask…") and comments on search results ("results conflict on timing"). The prompt now says to write only what the user should hear and never discuss the results; that removed the reasoning narration in testing.
- Location: coarse fused location reverse-geocoded on the phone resolved to a city about 0.5 s after the panel opened, well before the user finished speaking.
- Weather from web search is unreliable: results are snapshots, often two or more days old. Variants tried (same question, Haiku 5.5): as shipped, a hedged non-answer in 2.5 s; with "search again if outdated", a confident answer that reused the stale forecast and never searched again; with low thinking, two searches and a plausible answer but 8.5 s and garbled text. Hence the weather tool.

## Weather tool and tool loop (2026-10-20, Pixel 6a)

`get_weather` (phone-side tool calling Open-Meteo), run through a tool loop of up to 3 model rounds per turn. (It first sat behind a `WeatherProvider` interface; that was dropped once plan §4b made the tool itself the swap point: a keyed provider becomes a remote `get_weather` that overrides the local one.)

- Bifrost streams function calls in standard OpenAI format (`tool_calls` deltas: id and name first, then argument fragments; `finish_reason: "tool_calls"`), alongside Anthropic's web search in the same request.
- Gotcha: Bifrost also streams the server-side web search as a `tool_calls` entry with an empty id and name, in a response that already contains the answer. Treated as a phone tool, it was echoed back next turn and Anthropic rejected the history with a 400 (`tool_use.name: String should have at least 1 character`). Fix: only run tools when `finish_reason` is `tool_calls`, and skip entries without a name.
- A 24-hour forecast window wasn't enough for "tomorrow night" (the model improvised and corrected itself mid-sentence); 48 hours fixed it.
- One panel, six questions without tapping: weather here (1.9 s + 1.1 s for the tool round), tomorrow night from the earlier result (0.8 s), Denver via a named place (0.9 + 0.9 s), store hours and a market close via web search (2.6–3.0 s), then silence ended the follow-up listen quietly.

## Skills, rollout step 1 (2026-10-20, Pixel 6a)

`skills/<name>/SKILL.md` in the repo; a Gradle task (`bundleSkills`) copies `delivery: app` skills into the APK's assets; the app parses them and appends active skills (all listed tools registered) to the system prompt, sorted by name. Weather's rules moved from the base prompt into `skills/weather`.

- Verified: `Skills: weather (of 1)` at session start; the prompt sent to the gateway has the skill section and no weather rule left in the base; "What's the weather tomorrow afternoon" answered from the matching hours with one rounded reading (1.3 s + 0.8 s).
- Invoking while locked got the spoken "Unlock your phone first", as intended for v1.
- Search answers from Haiku with thinking off are sometimes hedged even when a result has the figure (a market close). Levers if it matters: low-effort thinking on search turns only (slower), or a larger model for search turns.
