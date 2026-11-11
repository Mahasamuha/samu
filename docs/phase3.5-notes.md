# Phase 3.5 notes: capabilities and late binding

Verification of `docs/phase3.5-plan.md` (Verification section). Results go here as they are checked.

## On device, production stack (2026-11-01)

Pixel 6a test phone, debug build v0.2.0, against the deployed gateway and MCP server (v0.2.0 images). Read from logcat.

| Check | Result |
|---|---|
| Index refreshed at every panel open; sessions used it (`index=fresh`) without waiting on it | Pass. The index held 2 capabilities (search, weather), 2 tools, 0 skills. |
| Call, text and timer requests: no `use_capability` | Pass |
| Call to a contact with one number of the type: announced, then placed | Pass (`Announce -> Confirmed`, `place_call -> calling`, panel closed) |
| Text: always asks | Pass (declined; `send_sms -> not_confirmed`) |
| Timer: reported, no confirmation | Pass |
| Weather: `get_weather` directly | Pass |
| Search: `use_capability(search)` then `web_search`, sources pill shown | Pass. The round after the bind began streaming 765–922 ms after the bind request. |
| Two searches in one session: the second has no bind | Pass |
| Contacts permission off: calling and texting not listed; navigation listed without `find_contact` and works | Pass |
| Sign out deletes the cache | Pass (`no_backup/` empty; auth state deleted too) |
| Hearing bubble after the ktlint reformat (modifier moved to the row) | Pass (looks unchanged) |

Seen along the way, not changed:

- Navigation defaults to Waze; on a phone without it the tool fails ("Waze isn't installed") and the model retries with Google Maps. The handled error is logged with a full stack trace.
- An action whose confirmation is pending when the panel closes leaves no log line (the cancellation skips the tool's outcome log). Harmless: nothing is performed.

## On device, dev stack (2026-11-01)

Same phone, pointed at the workstation dev stack (`compose.build.yaml`, built from this checkout) through the setup screen's gateway URL setting. The token from the identity provider worked unchanged on the dev gateway.

| Check | Result |
|---|---|
| Stale remote tool | Pass. Done mid-session rather than by renaming: MCP restarted without the Pirate Weather key, so `get_weather` was no longer registered. The next weather call logged "Server rejected get_weather (MCP error -32602: Tool get_weather not found); refreshing the capability index", the refresh returned 1 capability (search), and the phone's Open-Meteo `get_weather` answered. Tools stay bound for the session, so a later weather call in the same session was rejected again (about 0.3 s) before falling back; the next session uses the new index. |
| Manifest change without an app rebuild | Pass. MCP restarted with an edited manifest (`CAPABILITIES_FILE` override, `search` set to eager); after a refresh the session bound search eagerly, listed nothing lazy, and had no `use_capability`. In that session a weather question was answered with `web_search` instead of `get_weather`: one more reason to keep search lazy. |
| MCP stopped, cache present | Pass. The refresh failed (502); the session used `index=cache (1 min old)` with search listed; `web_search` failed as a tool error (the model tried twice); weather fell back to Open-Meteo. Each failed MCP request took 5 s at the gateway before its 502, so a failed search took about 15 s. Cause: the stopped container's name no longer resolves, and Docker's DNS forwarded it upstream with the default 5 s timeout (measured 5.03 s). Fixed with `dns_opt` on the gateway (one 1 s try; measured 1.0 s, live names a few ms). |
| No cache, MCP stopped (cache file deleted, app force-stopped) | Pass. `index=bundled`: no search listed; weather from Open-Meteo; the failed refresh ran in the background without delaying the session. Asked to search, the model said it couldn't find the information rather than that search is unavailable. |

## Build check (2026-11-01)

A temporary local tool (`ToolDefinition(name = "orphan_check_tool", …)`) in no capability failed `assembleDebug` at `:bundleCapabilities` with "Local tools in no local or hybrid capability in capabilities/capabilities.json: orphan_check_tool". Pass; the file was removed and the build passed again.

The Pixel 8 runs the build too (v0.3.0 code), against production.
