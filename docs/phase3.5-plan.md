# Phase 3.5 implementation plan: capabilities and late-bound tools

Status: in progress, 2026-10-31 (revised the same day: ownership tiers, on-device cache, optional tools). Phase 4's implementation plan (`docs/phase4-implementation.md`) builds on this.

## Context

Every session today builds every local tool, blocks on a fresh `tools/list` (`RemoteTools.load()`, 4 s timeout, `AssistantApp.sessionTools()`), and sends every tool definition plus every active skill's full text on every request (`VoicePrompt.system`). That doesn't scale to the tool count phase 4 brings (per-person connectors, upstream MCP servers like Constellation re-exposing many tools). Phase 3.5 switches to late binding. The model sees a list of **capabilities** (what Samu can do). A capability's tools, and the skills attached to it, are bound into the session when needed, from a cache on the phone. Phase 4 then plugs per-person availability into the server's index.

Decisions (this planning round):
- **Capabilities are a separate manifest**, not skills. A capability names its tools; skills attach to a capability.
- **Three ownership tiers**, derived from where a capability's tools live:
  - **Local** (only local tools): defined in the APK's bundled manifest. The server never sees them.
  - **Hybrid** (local and remote tools, or a `where: hybrid` tool): known to both. The APK owns whether the capability exists and which local tools it has. The server supplies its remote tools and their definitions, and can override `description` and `load`.
  - **Server** (only remote tools): defined by the server. A server capability can't include a local tool; one that needs one is hybrid by definition.
- **The capability index is cached on the phone, not on the server.** Capabilities change slowly. The phone keeps the last index on disk, refreshes it out of band, and never blocks a session on it.
- **Eager + lazy.** Each capability declares `load: eager | lazy`. Eager ones are bound at session start, as all tools are today. Lazy ones are listed by name and description and bound through a phone-side `use_capability` tool, at the cost of one extra model round the first time in a session. Binding is a local lookup with no network call.
- **Tools can be optional within a capability.** A capability is listed when its required tools are available; its optional tools are bound when present.
- **No prompt caching.** Samu's prompts are likely below the provider's minimum cacheable length, and sessions are short and infrequent. Changing the tool list mid-session therefore costs nothing extra. Revisit if prompts grow (e.g. many eager skills).
- Prior art: Claude Code's deferred tools (names listed, a search tool returns schemas) and Agent Skills progressive disclosure. Anthropic's server-side tool search was rejected: requests go through Bifrost's Responses translation, and it still needs every definition sent with the request.

## Model

```
Capability {
  id            "weather"
  title         "Weather"
  description   one line for the model: what it does and when to use it
  load          eager | lazy
  tools         [{name:"get_weather", where: local|remote|hybrid, required: true}]   // required defaults to true
}
Skill (SKILL.md) gains:  capabilities: [weather]   // attached; loaded with any of them
```

- **Tools and capabilities are many-to-many.** `find_contact` serves calling, texting and navigation. Binding a capability binds whichever of its tools aren't bound yet.
- **One repo manifest, two consumers.** `capabilities/capabilities.json` is the source for both sides. Gradle bundles the local and hybrid entries into the APK. The MCP server serves the hybrid and server entries. The tier is derived from each entry's tools; nothing declares it separately.
- **Build-time orphan check.** Every local tool the app registers must belong to a local or hybrid capability in the bundled manifest, or the Gradle build fails. The APK is self-consistent, so the order of app and server deploys doesn't matter.
- **Optional tools and skills.** An attached skill can't assume an optional tool exists; its text says so where it matters ("if find_contact is available…"). The phone does not filter skill text by tool.

### The index (server)

- One MCP resource on the existing `/mcp` route, so the gateway allowlist doesn't change: `capability://index` returns the hybrid and server capabilities available to the caller, every remote tool definition they reference, and their attached server-side skills. Everything a bind needs is in it; there's no per-capability resource.
- Phase 4 adds per-principal filtering and connector capabilities from the accounts catalog; their text always comes from the catalog, never from upstream servers.
- Private remote tools (`music_find`) appear in no capability and are never listed, as today.

### The cache (phone)

- The last successful index is stored in app-private storage, excluded from backup. It holds non-secret definitions only. It's keyed to the signed-in principal and deleted on sign-out.
- **Refresh at panel open**, in parallel with listening. The refresh races speech-to-text: if it lands before the first model request, this session uses it; otherwise the session uses the cached index and the refresh applies to the next session. A failed refresh keeps the existing cache.
- **Manual refresh** from a button in the setup app.
- **First run, or no cache:** the session uses the bundled manifest alone. Local capabilities work; hybrid capabilities fall back to their local tools (weather → Open-Meteo); server capabilities appear after the first successful refresh.
- **Stale definitions:** if a remote tool from the cache no longer exists on the server, the call fails as a normal tool error and the phone schedules a refresh.

### Merging (per session, on the phone)

- Local capabilities: from the bundled manifest.
- Hybrid capabilities: existence and local tools from the bundled manifest; remote tools, remote definitions, `description` and `load` from the cached index when present, bundled values otherwise. A hybrid id in the index that the APK doesn't know is ignored and logged.
- Server capabilities: from the cached index.
- Hybrid tools: `where: hybrid` means the remote tool is preferred and the local one stays behind it as `FallbackTool`, as today.
- **What the phone still decides:**
  - Which local tools are available (permissions). A capability is listed only if all its required tools are available, local ones by permission and remote ones by presence in the cached index.
  - The `allowRemote`/`reserved` rules: the server can't take over a local tool.
  - Confirmation, per tool, as today.
- Skills attach through `capabilities: [..]` and are active when any of their capabilities is bound. Skills with no `capabilities` stay always-on (instruction-only skills).

## Session flow

1. Panel open: start the index refresh (above); build local candidates by permission; merge the bundled manifest with the cached index (or the fresh one, if it has arrived by the first model request).
2. Bind eager capabilities. System prompt = base, then eager capabilities' skills (sorted, as today), then a `# Capabilities` section listing lazy ones (`- search: …`, sorted), then context. The system prompt doesn't change after this point.
3. `use_capability(id)` is a local read-only tool, not open-world. It binds the capability from the merged catalog: local tools from the phone, remote definitions from the cache. It returns `{capability, tools:[names], instructions}`, where `instructions` are the attached skills' text. Lazily bound skills reach the model only through this result, never through the system prompt. Repeat binds are no-ops; an unknown or unavailable id returns an error listing the available ids.
4. `respond()` reads the registry's definitions **per model round**, not once per turn, so tools bound in round N are sent in round N+1. Bindings last for the session.
5. Base prompt rule: "If a request needs a capability listed under Capabilities, call use_capability first, then continue. Say nothing before it."
6. Log per session: index source (fresh, cache, bundled), capabilities listed, bound eagerly, bound lazily, and the extra round's latency. This is the data for tuning eager vs lazy.

Safety unchanged: confirmation stays per tool in `ToolRegistry.execute`. Binding only adds definitions; a prompt-injected `use_capability` gains nothing a session couldn't already reach today, when everything is eager.

Initial load settings (tune from logs): eager = calling, texting, clock, music, navigation, weather; lazy = search. Weather is likely the most frequent query, so it doesn't pay the extra round.

## Work packages (Sonnet implementors, one commit each)

### WP-K: docs (me)
- `docs/plan.md`: decisions row (capabilities + late binding, ownership tiers, on-device cache, prior art, why not server-side tool search), §4b rewrite of "Discovery" and "Loading", §5 phase list (3.5 inserted; phase 4 depends on it).
- `CLAUDE.md` current phase; `skills/README.md` (`capabilities:` field).
- `docs/phase4-plan.md`: §4 MCP service note that per-principal availability is expressed through `capability://index`, and that the phone's cache is keyed to the principal.

### WP-1: manifest + skills (first; both sides depend on it)
- New `capabilities/capabilities.json` + `capabilities/README.md` (format, tiers and how they're derived, many-to-many rule, `where`, `load`, `required`, hybrid merge rule). Initial entries:

  | Capability | Tier | Load | Tools |
  |---|---|---|---|
  | calling | local | eager | `find_contact`, `place_call` |
  | texting | local | eager | `find_contact`, `send_sms` |
  | clock | local | eager | `set_timer`, `set_alarm` |
  | music | local | eager | `play_music`, `music_control` |
  | navigation | local | eager | `navigate`, `find_contact` (optional) |
  | weather | hybrid | eager | `get_weather` (hybrid) |
  | search | server | lazy | `web_search` (remote) |
- `skills/*/SKILL.md`: add `capabilities: [..]`. New `skills/search/SKILL.md` (`delivery: app`, `capabilities: [search]`); move the base prompt's search bullets (`VoicePrompt.kt` "# Answering", first three) into it. The navigation skill's text covers `find_contact` being absent. `skills/README.md` documents the field.

### WP-2: MCP server index resource (after WP-1; parallel with WP-3)
- New `mcp/src/capabilities.ts`: loads the manifest at startup and keeps the hybrid and server entries. Per request, it filters to capabilities whose required remote tools registered in this request's `buildServer()`, and builds `capability://index` (summaries, all referenced remote tool definitions taken from the registered tools, attached server-side skills).
- `mcp/src/index.ts`: `server.registerResource` for `capability://index`.
- Docker build: the manifest lives outside `mcp/`. Change the `mcp` build context in `.github/workflows/images.yml` and `deploy/compose.build.yaml` to the repo root with `file: mcp/Dockerfile`, and copy `capabilities/` (and later `skills/`) into the image. Keep `.dockerignore` tight; `app/` must be excluded.
- Verify with `resources/list` / `resources/read` through the dev stack.

### WP-3: app manifest, cache + client (after WP-1)
- Gradle copies the local and hybrid entries of `capabilities/capabilities.json` into assets (same mechanism as bundled skills), and fails the build on a local tool that belongs to no local or hybrid capability.
- New `app/.../capabilities/Capability.kt` (data classes, `Load`, `Where`, `required`, JSON parsing shared by the bundled and fetched forms), `BundledCapabilities`, and `CapabilityCache` (disk storage keyed to the principal, cleared on sign-out; `refresh()`; the merge rules above).
- `skills/Skill.kt`: parse `capabilities: [..]` (same `[a, b]` subset as `tools`).
- `remote/McpClient.kt`: add `readResource(uri)`. `RemoteTools` is replaced by `CapabilityCache.refresh()`, which reads `capability://index`; executing remote tools stays on `McpClient`.
- Setup app: a "Refresh capabilities" button showing the cache's age and source.

### WP-4: session binding (after WP-3)
- `AssistantApp.sessionTools()` → returns a `SessionCatalog`:
  - Capabilities available this session: merged catalog ∩ local tools by permission ∩ remote tools present, by required tools. Existing `reserved`/`privateRemoteTools`/`allowRemote`/`FallbackTool` logic kept.
  - A registry with eager capabilities bound. Tools are shared across capabilities, so a bind skips tools already bound; optional tools are bound only when available.
- Panel open starts `CapabilityCache.refresh()`; the session takes its result if it completes before the first model request.
- `tools/Tool.kt` `ToolRegistry`: add `bind(tools)` with the same uniqueness/`readOnlyHint` checks; `definitions` becomes a live view.
- New `capabilities/UseCapabilityTool.kt` (ReadOnlyTool).
- `voice/VoiceSessionController.kt`: build `ChatOptions(tools = …)` inside the round loop in `respond()`; logging per above. A remote "unknown tool" error schedules a cache refresh.
- `voice/VoicePrompt.kt`: `system(eagerSkills, lazyCapabilities, location)`; the capabilities section; the `use_capability` rule.

## Verification

- `./gradlew :app:assembleDebug`; `pnpm typecheck` in `mcp/`; dev stack via `compose.build.yaml`.
- Build check: a local tool with no capability fails the Gradle build.
- On the Pixel 8, logcat for each:
  - "Call Mom" / "text Sam I'm late" / "timer 10 minutes": no `use_capability`, same latency as today.
  - "What's the weather tomorrow": no `use_capability`, `get_weather` directly, answer correct.
  - "Who won the game last night": `use_capability(search)` → `web_search`; sources pill still shown; note the extra-round latency (no network call in the bind).
  - Two search questions in one session: second has no bind.
  - Index source logged as fresh when the refresh beats speech-to-text, cache otherwise.
  - MCP server stopped, cache present: session uses the cached index; search is listed; a search call fails as a tool error.
  - Fresh install (no cache), MCP server stopped: bundled manifest only; weather uses the local Open-Meteo tool; search isn't listed.
  - Edit a description or flip `load` for search or weather in the manifest, redeploy only the MCP image, tap refresh in the setup app: next session reflects it with no app rebuild.
  - Contacts permission off: calling and texting not listed; navigation listed without `find_contact`, and "navigate to the hardware store" works.
  - Sign out: cache file deleted.
- Record results and latencies in `docs/phase3.5-notes.md`.

## Open issues

- **Resolved: stale remote tool detection** (2026-11-01). The SDK (1.32.1) returns its protocol errors from `tools/call` as an `isError` result with text `MCP error <code>: ...`, not as a JSON-RPC error, so the original JSON-RPC check never fired. The phone now refreshes the index on `MCP error -32602:` (unknown or disabled tool, rejected arguments), in either form. Tool-handler failures and transport failures don't refresh. Arguments the server no longer knows are silently dropped by the SDK, so that change can't be detected and there is no workaround; the next refresh at panel open picks it up.
