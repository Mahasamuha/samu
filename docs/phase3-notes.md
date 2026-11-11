# Phase 3 notes

Findings from building and testing tools and skills (plan §4b) on the test phone (Pixel 6a, Android 17).

## Confirmation flow and SMS (2026-10-21)

- The flow works end to end: an ask is spoken and shown with Confirm/Cancel, the reply is judged on the phone, and a `not_confirmed` result lets the model revise ("no, say I'm ten minutes late" leads to a new read-back; "no, the other Sam" to a new lookup).
- `SmsManager` sends report `RESULT_OK` from the radio for fictional 555-01xx numbers too: "sent" means the carrier accepted it, not that it was delivered.
- With several declined sends in one session, the model (thinking off) started copying the pattern: answering a fresh request with "Okay, I won't send it", or asking for confirmation itself. Declines are now scoped in the tool result ("only this action") and the base prompt.
- The model reused an earlier `find_contact` result instead of looking up again, so a contact added mid-session was never offered. The base prompt now asks for a fresh lookup per request.
- A number saved under two contacts was read back as whichever contact the reverse lookup returned first. The read-back now names all of them, phrased as a statement ("the number saved as A and B"), because "A or B?" inside a yes/no question invited a name as the answer.
- A lone spoken "no" sometimes comes back from the on-device recognizer as no match (error 7). Confirmation listening now passes `EXTRA_BIASING_STRINGS` with yes/no words; in the first test the "no" was recognized. An unrecognized reply is treated as not confirmed and shown as a "no reply heard · cancelled" note.

## Calls (2026-10-22)

- An `ACTION_CALL` activity from the session was allowed (`BAL_ALLOW_NON_APP_VISIBLE_WINDOW`) but resolved to the system chooser, because Zoom also handles `ACTION_CALL`; the chooser opened behind the panel and no call happened. Calls now go through `TelecomManager.placeCall`, which needs `CALL_PHONE` and no activity resolution. The session closes itself once the call is placed.
- Announce-and-dial for a certain match and "which one?" for different numbers both worked.

## Keystore while "unlocked"

- The token key (`setUnlockedDeviceRequired`) refused to decrypt (`UserNotAuthenticatedException`) while the screen showed unlocked: the phone had started to lock and was woken within the grace period without a fingerprint or PIN. A real unlock fixes it; the app now says so.

## Thinking: `reasoning_effort` "none" vs "low" (2026-10-22)

Even with prompt rules against it, Haiku 5.5 with thinking off kept narrating its reasoning aloud in some answers ("The user wants to call 555, which isn't a complete number…", "The 2025 World Series is a current event relative to my training, so I'll check."), and once invented a contact option before looking anything up. Logged requests from the session were replayed through Bifrost, two runs per case (time to the first text or tool call / total):

| Case | none | low |
|---|---|---|
| Contact lookup turn | 0.7–1.3 s | 0.5–2.3 s |
| Weather (tool call) | 0.7–0.9 s | 0.6–0.7 s |
| Web search question | 0.6–1.2 s first text (a narrated preamble), 2.6–3.3 s total | 2.9–5.4 s, answer in one piece |
| Plain question | 1.1 s | 1.9 s |

`low` produced no narration in any case and dropped the "Let me find…" preambles. It costs roughly 0.5–2 s before the first word on plain and search answers; tool-call turns are unchanged. `low` works within a 400-token cap (99 thinking tokens on a plain question); the cap is now 1000 for headroom. Switched the app to `low`.

On the phone with `low` (2026-10-22): no narration in a session of weather, a call, two texts with corrections, and a search question. Turns with thinking took about 1–4 s to the first word, the search question 5.8 s; the user judged the delay normal for an assistant and kept `low`. One search answer ended with raw figures ("…about 360 points. 52,856.09 +360.21 (+0.69%)"), which would be read aloud; three replays of the same request gave clean sentences (one named its source, against the prompt). Watch for it before adding a speech-side filter.

## Web search answers (2026-10-22)

- **Quoted source text read aloud.** Search answers often ended with a verbatim snippet ("7,712.57 +21.84 (+0.28%) At close: October 20 at 4:45:45 PM EDT"). On Bifrost's native Anthropic route the cause is visible: Claude answers in several text blocks, and the cited block's text is the source sentence nearly word for word (equal to its `cited_text`); citations are always on for web search, and the chat-completions route concatenates all text blocks. Not a Bifrost injection. A base-prompt rule ("answer in your own words in one or two sentences and stop; never quote or repeat source text; never add a second version") cut it from 4 of 5 replays to 0 of 6.
- **Retracting its own correct answers.** The chat-completions route doesn't return search results, so the phone's history holds only the spoken sentence. On a follow-up the model saw an unsupported figure and announced it had made it up, then searched and found the same figure. A base-prompt rule (earlier answers came from searches whose results it can no longer see; don't retract unless asked) stopped it in 5 of 5 replays. If this recurs, the next step is marking searched answers in history.

## Responses API spike (2026-10-22)

The chat-completions route flattens Claude's answer blocks into one string and drops search results, which caused both problems above. Bifrost also serves the OpenAI Responses API (`POST /v1/responses`); probed with the app's real system prompt and tools:

- **Typed output.** A search comes back as a `web_search_call` item (query and sources), the answer as `output_text` with citations as `url_citation` annotations, reasoning as its own item. Phone tools round-trip as `function_call` / `function_call_output` items (a tool with no parameters arrives with empty `arguments`). Streaming uses typed events (`response.output_text.delta`, `response.function_call_arguments.delta`, `response.completed`).
- **Searches survive in history.** Sending the output items back as input lets follow-ups see earlier searches: asked "where did you get that number?", the model named the sites. Reasoning items can't be sent back (Anthropic: "thinking blocks … cannot be modified"; Bifrost doesn't keep the signatures), so history drops them.
- **Quoted source text.** In the probes, answers on this route were clean even without the no-quoting rule; the rule stays as a backstop, since a cited block can still be source-like `output_text`.
- **Cost.** Each search kept in history adds about 15,000 input tokens to every later turn of the session (three-turn conversation: 18,215 / 33,454 / 20,133 input tokens on Responses versus 18,277 / 18,239 / 5,154 on chat completions). Bifrost's logged cost: about $0.0016 more per later turn per kept search.
- **Tracking.** Bifrost logs `responses` and `responses_stream` requests under the phone's virtual key with cost (search fee included) and attributes them to the same budget (`budget-phone`). Budget enforcement itself was verified on chat completions in phase 2, not re-tested here.
- **Latency.** Comparable; the larger history adds a little (2.4 s versus 1.7 s on a no-search follow-up).

Switched (2026-10-22): the app's `GatewayChatClient` now speaks Responses; assistant turns keep the returned items (minus reasoning) and send them back verbatim. The gateway allowlist has `POST /v1/responses`; chat completions stays until the phone test passes. Checked: Responses bodies, streamed and not, contain no `extra_fields` or provider account IDs (Bifrost's metadata stays in `x-bifrost-*` headers, which the gateway doesn't forward). The "don't retract earlier search answers" prompt rule is gone, since the searches are now in history; the no-quoting rule stays.

Thinking off on Responses (2026-10-22), 3 runs each with the app's prompt: narration came back on search questions in 3 of 3 ("The 2025 World Series is last year relative to 2026, so I'll look it up."), plus muddled visible reasoning on the S&P question; `low` was clean. So narration is a property of thinking off, not of the endpoint; `low` stays. With `low`, one of three S&P answers still ended with a source fragment ("At close: October 20 at 4:00:00 PM EDT"): rarer on Responses with the no-quoting rule, not gone.

First phone test on Responses (2026-10-22): searches were not reaching history (0 `web_search_call` items in every request), so three turns later the model again "retracted" a correct Dow figure. Cause: in a streamed response Bifrost's final `response.completed` omits the `web_search_call` items, though each one arrives in its `response.output_item.done` event. The client now builds the turn from `output_item.done` events; a streamed search replayed this way lets a follow-up name its sources. Also seen: streamed message items carry no citation annotations, and a cited answer arrives as several message items that interleave the model's words with source text, so quoted fragments can't be dropped structurally. The no-quoting prompt rule remains the only lever on this route; a search tool of our own (plan §4b step 4) would remove citation blocks entirely.

Sources pill (2026-10-22): the first version listed every result of the turn's own searches, so a follow-up answered from an earlier search ("and the Dow?" after an S&P search whose market-wide pages included the Dow) got no pill. Streamed message items carry no annotations, but Bifrost does stream `response.output_text.annotation.added` events (`url_citation` with url, title and the cited text), including on answers that cite an earlier turn's search. The pill now lists the cited pages, falling back to the turn's search results (labeled "search results") when an answer cites nothing.

## Weather by date (2026-10-22)

`get_weather` takes optional `day` and `end_day` (YYYY-MM-DD) instead of returning 48 hours plus every day for the model to pick from. No day: current conditions, today's high/low, and hours until 6 AM tomorrow. One day: that day's summary and its hours every 3 hours. A range: one summary per day. It fetches only as many days as needed (up to Open-Meteo's 16) and rejects past dates or dates beyond the forecast with a message the model can relay. Replayed through Bifrost (Wednesday evening), the model chose: tonight / "what's it like outside" → no day; "tomorrow afternoon" → Thursday; "day after tomorrow" → Friday; "this weekend" → Saturday–Sunday; "next Thursday in Chicago" → Chicago, the Thursday a week out.

On the phone: today, "the next 2 days" (a range), next Wednesday, "5 days from now", and "14 days from now" each fetched the right date and got one short answer; "7 days from now" was recognized as the Wednesday already answered; "3 days ago" and "30 days from now" got the tool's refusals, relayed in one sentence. The wordy, repetitive weather answers from phase 2 are gone.
Parts of a day work from the same one-day result: "tomorrow afternoon" (Thursday) was answered from the afternoon hours ("clear, a high around 67 by 3 PM"), and "and Friday morning" from the morning hours ("around 47 at 6 AM, about 50 by 9 AM").

## Own search tool spike: Brave Search API (2026-10-22)

Bing's API is retired and Google's Custom Search closed to new customers; Brave (own index, sold for AI answers, $5 per 1,000 with $5 monthly credit) was chosen to try, with Tavily as the fallback. The key lives only in the untracked `deploy/.env`.

Brave endpoints, 5 results each: `web/search` with `extra_snippets` answers in about 0.6 s with about 1,000–2,200 tokens, but its snippets often lack the figure asked for (no S&P or Dow close). `llm/context` (GET `/res/v1/llm/context?q=…`, response `grounding.generic[]` of `{url, title, snippets[]}`) takes about 1.2–1.6 s and returns about 8,000–12,500 tokens of extracted page text that includes the figures.

End to end through Bifrost with the app's prompt, `web_search` as a function tool backed by Brave (result capped at about 6,000 tokens) against Anthropic's server tool:

| Question | Anthropic search | Brave `llm/context` | Brave `web/search` |
|---|---|---|---|
| S&P close | 7.4 s, right | 2.8 s, right | 3.5 s, gave yesterday's close |
| Dow close | 4.9 s, right plus a quoted fragment | 3.3 s, right | 3.7 s, right |
| 2025 World Series | 5.1 s, right | 5.3 s, right | 4.0 s, right |
| Artemis news | 5.0 s, plus a quoted headline | 4.8 s, right | 2.4 s, right |

Brave `llm/context` was right on all four, never quoted source text (no citation blocks exist on this path), and was as fast or faster despite the extra model round. Input tokens were similar (about 15,000–17,000 per question at the 6,000-token cap); a tighter cap would cut them. Cost per search: $0.005 versus $0.01 plus similar tokens.

## Step 4 built: MCP server and remote web search (2026-10-22)

- `mcp/`: TypeScript, official MCP SDK (1.32.1), Streamable HTTP in stateless mode with plain-JSON responses (`sessionIdGenerator: undefined`, `enableJsonResponse: true`), a fresh server per request. One tool, `web_search(query)` (read-only), calling Brave `llm/context` and returning `{results: [{title, url, date, text}]}`. Brave returns about 30 pages, many with a fragment of text; taking all of them until a character budget filled gave 37 tiny results. Now the top 8 pages, each with an equal share of 24,000 characters: about 900–2,200 tokens per search in practice, with the figures asked for on the top page. A Brave call takes about 1.4–2 s.
- Deploy: an `mcp` service on the private `backend` network, no published port, `BRAVE_API_KEY` from `deploy/.env`.
- Gateway: `POST /mcp` (same token check and rate limit), forwarded with no key; `accept` and `mcp-protocol-version` pass through. The route table now maps each allowed path to its upstream. `GET`/`DELETE /mcp` are 404 (no sessions or event streams in stateless mode).
- Phone: `McpClient` (about 150 lines; `tools/list`, `tools/call`; no `initialize`, since a stateless server can't remember it). Remote tools load while the user speaks and join the registry (remote overrides local by name); read-only ones run directly, others get a phone-built Ask. Anthropic's server-side search and its citation handling are gone from the chat client; the sources pill now reads `web_search` results and attaches to the answer bubble after the search.

First phone session on our own search (2026-10-22): the session's tools included the remote `web_search` next to the local ones. "What did the Dow do today" and "what did Palantir do" each ran one Brave search (about 1.3–1.6 s) and got one clean sentence ("The Dow closed up about 360 points today, or 0.7%, at around 52,856."); "and the Nasdaq" and "the S&P 500" were answered correctly from the Dow search's market-wide page without searching again. No quoted fragments. Each model round took about 0.8 s, a search answer about 3 s end to end (5–7 s with Anthropic's search). Input tokens after a search were about 5,000–10,000 per turn, against 18,000–33,000 with Anthropic's search carried in history. A call (announced, cancelled) and a text (read back, sent) worked in the same session.

## Weather via Pirate Weather (2026-10-22)

`get_weather` on the MCP server, registered only when `PIRATE_WEATHER_API_KEY` is set: Open-Meteo geocoding for the place ("City, Region" picks the match whose region or country fits), then Pirate Weather (`extend=hourly` for 168 hours; 8 days; key in the `apikey` header, with a placeholder in the path). The same output shape as the phone's tool, plus `next_hour` (the minutely summary, e.g. "Clear for the hour.") and `alerts` (title, severity, until, description). Local times use the forecast's IANA time zone. From the gateway container: now, one day, a weekend range, an out-of-range date ("The forecast only reaches Wednesday, October 28"), and an unknown place each behaved, in 0.1–0.7 s. On the phone, a remote read-only tool that shadows a local one is wrapped so the local one answers on any failure, including the server's own refusals (a date past Pirate Weather's 7 days falls through to Open-Meteo's 16). The weather skill now always passes a place and mentions alerts first.

On the phone (Pirate Weather): now, tomorrow, "will it rain in the next hour" (answered from `next_hour` without another call), "any weather alerts" (none in effect), and a named city on Saturday all came from the server in about 0.6–0.7 s per call; the user's city went as a name, so coordinates stayed on the phone. For a bare city name the geocoder picked the largest match, and the answer didn't say which one until asked.

Springfield (2026-10-22): asked about a Springfield, the model passed the city with a state, but the server answered for Springfield, Missouri, and three reworded retries did the same. The geocoder asked for 10 matches, a smaller Springfield wasn't among them, and the qualifier check fell back to the first match. Now it asks for 100, matches the qualifier against state, county, country, and country code (with US state abbreviations), and refuses rather than guesses when nothing fits, which lets the phone's Open-Meteo tool try instead. Checked with "Springfield, Oregon", "Springfield, OR, USA", "Springfield, Lane County, Oregon", "Springfield, Illinois", and "Portland, Oregon"; a bare "Springfield" is Missouri, which the answer now names.

Security review fixes, checked on the phone (2026-10-22): a call to a contact in a fresh session was only announced, and after a search in the same session it asked first; a star code (`*#06#`) was refused; the panel kept the screen on while in use; with the gateway stopped, the phone said it couldn't reach the server after 15 s instead of waiting out the read timeout; with a test number added through `cmd phone emergency-number-test-mode`, "call 5 5 5 0 1 0 0" opened the dialer prefilled rather than calling. A text to a saved contact's number with a different area code, or with only its last 7 digits, was not matched to that contact, so the read-back gave the digits: the contact lookup is stricter than the reviewer feared, which is the safe direction.

Deployment (2026-10-22): the gateway and MCP images are published to Docker Hub by a GitHub Actions workflow (smoke-tested read-only against `/healthz` before each push) and pulled on the home server, which holds only `deploy/` plus its gitignored env files. The gateway joins the reverse proxy's Docker network under the alias `samu-gateway` and publishes no port; body logging is off (`LOG_BODY_RETENTION=0`); Bifrost started with fresh spend tracking. From outside, `/healthz` answered, an unauthenticated `POST /v1/responses` got 401, and an unlisted path got 404. A search and a weather question from the phone worked through the deployed stack.

Navigation and options (2026-10-22, Pixel 8): `navigate` opened Waze or Google Maps for a named place, the saved work address, and a contact's address; with device location off, the one-tap Play services dialog appeared after the panel closed and navigation started on OK. The voice picker lists nine on-device voices on the Pixel 8 (network voices excluded); the chosen voice was used by the next session.

Uncommon contact names (2026-10-22): speech recognition wrote them as more common spellings, so the provider's prefix filter found nothing. `EXTRA_ENABLE_BIASING_DEVICE_CONTEXT` made no visible difference and was removed; passing all ~240 contact names as `EXTRA_BIASING_STRINGS` was not tried (no documented limit, and a long list risks pulling ordinary words toward names). A sound-alike fallback works: in two logged lookups the right contact scored 0.96–1.00 and the next-best wrong one 0.08–0.12 lower, with other noise from 0.75 up, so candidates must score at least 0.80 and be within 0.06 of the best. In a sample test, Irish spellings (Ciara, Aoife) scored poorly; a nickname on the contact is the dependable fix.

## Open issues from use (2026-10-24, not yet investigated)

- **First request not heard.** Speech recognition sometimes doesn't register the first request of a session; closing and reopening the assistant is the only way to get it listening. **Investigated 2026-11-10: the cause is clipping by a cold recognizer; fixed behind a debug toggle (piped STT). See "Cold-start clipping and piped speech recognition" below.**
  - Reproduced 2026-11-01 on the Pixel 8 by force-stopping Android System Intelligence (`com.google.android.as`, which serves the on-device recognizer) and invoking straight after. `STT ready` came 340 ms after the session opened and the hearing bubble showed partial text (starting about 1.5 s after speech began), but the listen ended 11 s later in `error 7` (no match) and the turn failed with "I didn't catch that." A warm run straight after was heard normally (transcript about 3.8 s after ready). One run each; repeat before relying on it.
  - The app drops partials when a listen ends in an error (`PlatformSpeechToText`), and creates and destroys a recognizer per listen. The `#cancel received for a listener which has not started a session` warnings come from `awaitClose` cancelling after a result; harmless.
  - Candidate fixes: log `onBeginningOfSpeech`, `onEndOfSpeech` and the first partial's time, to measure the lag and the cold failure; on `error 7` after partials, use the last partial as the transcript; reuse one recognizer per session (or keep one warm) and measure whether it helps.
  - Transcription generally lags speech noticeably (partials trail by a second or more).
- **Investigate our own recognizer: what would it cost?** (Parked 2026-11-10: piped audio fixed the cold start while keeping Google's model. The on-device comparison of sherpa-onnx and Moonshine is summarised in the section below, in case piped mode fails in daily use.) The platform recognizer's cold-start failures and lag are outside our control. STT already sits behind the `SpeechToText` interface, so a replacement is a swap, not a redesign. To find out per option: on-device streaming models (e.g. sherpa-onnx with a streaming Zipformer, whisper.cpp, Vosk), a self-hosted model on the home server reached through the gateway, and a hosted STT API. For each: time to first partial and to final on the Pixel 8, accuracy (including uncommon contact names), APK size or model download, RAM, battery and heat, streaming support, money per minute for hosted options, and privacy (audio leaving the phone conflicts with the plan's no-record intent unless it is transient). Capturing audio ourselves (`AudioRecord`) happens only while the session is visible, so the background-audio restriction (plan §3) shouldn't apply; verify.
- **"Play some music" is incoherent.** An unqualified request plays truly random tracks with no genre or thread, rather than picking something and staying with it.
- **Sonos Move playback.** Asked to play on the Sonos Move, it seemed to target a group coordinator although the Move is a single speaker: the Move looked half-selected but nothing played until it was tapped in the app's cast options and play was pressed manually.

## Suggestions from review and testing (2026-11-01, not scheduled)

Raised during the phase 3.5 review and on-device checks; tracked here so they aren't lost. None is a correctness problem today.

- **Speech recognition, order of work.** (Done or superseded 2026-11-10; see the cold-start section below.) Log the speech events first (cheap, and makes the rest measurable), then fall back to the last partial on `error 7`, then try reusing one recognizer and measure. For our own recognizer, start the cost check with sherpa-onnx and a streaming Zipformer model: it streams partials, runs on-device, and is permissively licensed. That's a starting point to test, not a verdict.
- **Gateway URL setting: check the server on save (will do).** Today a URL whose host doesn't resolve is saved without complaint, and the refresh then fails as a bare "Capability index refresh failed" (Android's `Log` drops the stack trace for unknown-host errors, so the cause isn't visible). Plan:
  - Saving calls `GET /healthz` on the new URL (unauthenticated, already on the allowlist) and saves only if it answers.
  - `/healthz` also returns the oldest app version the server supports (e.g. `{"status":"ok","version":"0.3.0","minAppVersion":"0.3.0"}`); the app refuses a server that needs a newer app and says so.
  - Name the failure on the setup screen: host not found, can't connect, not a Samu gateway, app too old.
  - To decide when building: the field name; where the minimum comes from (a constant in the gateway bumped deliberately on breaking changes, or env); how dev builds (`0.2.0-3-g…`) compare (by the release part); whether the app also checks at panel open, not only on save. Record the choice in the plan's decisions table, since it extends the `/healthz` response.
- **Failed remote calls get retried by the model.** With the MCP server down, the model called `web_search` twice in a row. A transport failure (502, unreachable) could say "unavailable right now, don't retry" in the tool error.
- **Navigation without Waze.** Waze is the default; on a phone without it the tool fails and the model retries with Google Maps, and the handled error is logged with a full stack trace. Options: default to whichever maps app is installed, and log it without the trace.
- **Actions dropped when the panel closes leave no log line.** The coroutine's cancellation skips the tool's outcome log, which made one session unreadable in testing. A single "cancelled (session closed)" line would fix it.
- **Verify `dns_opt` on the home server.** `docker compose exec gateway cat /etc/resolv.conf` should show `timeout:1 attempts:1 edns0`.
- **Decided against:** telling the model in the prompt when a server capability is unreachable (the "can't find that information" answer when search is missing was judged acceptable).

## Cold-start clipping and piped speech recognition (2026-11-10)

### What was wrong

Measured on the Pixel 8 with Android System Intelligence force-stopped before each run (the service process restarts at once, but its speech engine, SODA, loads only on the next recognition). Logs now give every listen event as milliseconds since listening started.

- `onReadyForSpeech` fires when the microphone opens (about 0.5 s in), not when the engine processes audio. On a cold start the engine took 1.6 to 7.4 s to load (`Initializing SODA` → `start detection`); speech in that gap was lost. The Pixel 6a, measured once, did not reproduce it.
- `onRmsChanged` is no better as a readiness signal from the platform recognizer: its first reading came about 1.3 s before the engine started processing.
- The error-7 failure seen on 2026-11-01 is a second symptom: partials, then no match. The app now uses the last partial as the transcript in that case.

### The fix: we own the microphone

`PipedSpeechToText` (debug toggle "Experimental: feed recognizer our own audio" on the setup screen; `PlatformSpeechToText` stays the default):

- Records with `AudioRecord` (`VOICE_RECOGNITION`, 16 kHz mono PCM16) from the start of each listen and feeds Google's on-device recognizer through `RecognizerIntent.EXTRA_AUDIO_SOURCE` (a pipe). The recognizer honours it (`Create input stream from external audio PFD`; it opens no microphone of its own).
- **The engine discards audio it reads while loading.** With a 7.4 s load, 8 s of piped audio produced empty results; with 5.7 s only the end of a long utterance survived; with 1.6 s everything was kept. So real audio is held back: until the recognizer's first `onRmsChanged` (about 125 ms after the engine starts processing, every run), we write paced silence and queue the real audio, then deliver the queue from its first frame. Fallback after 10 s. Confirmed: a 5.35 s cold start kept the opening words.
- **With piped audio the recognizer never ends the session on its own**; it keeps per-utterance results until the pipe closes. The session is segmented (`EXTRA_SEGMENTED_SESSION`), segments are joined, and we decide when to close the pipe, in audio time (the recognizer's callbacks run in processing time while it catches up on a backlog). A WebRTC voice activity detector ([gkonovalov/android-vad](https://github.com/gkonovalov/android-vad), MIT, about 0.6 MB of native libraries for four ABIs) classifies 20 ms frames. Rule: once the recognizer has produced text, 0.8 s of silence ends the listen; before that, only 4 s of silence on a reply listen (6 s on a session's first listen) ends it. Desk thumps and Samu's own tail are flagged as speech by the detector, so they never arm the 0.8 s rule; they only reset the wait. A 15 s cap is the backstop. If the endpoint comes while the engine is still loading, the queue still waits for it.
- **Feedback:** a large level-driven indicator in the middle of the panel, shown only while the engine is warming (not up 300 ms into the listen); otherwise the header shows "samu · listening" or "samu · transcribing" with a level dot. Levels on the Pixel 8: speech peaks about −22 to −33 dBFS, typical −37 to −49; the indicator maps −60..−25 dBFS.
- Speech before `onShow` (talking during the gesture) is still lost; nothing runs before the session starts. Recording starts about 200 ms after the session is created; starting it in `onCreate` might gain that, unverified.

To watch in daily use before making it the default: cut-offs mid-sentence (0.8 s), noisy places (car, outdoors), yes/no confirmations, and continuous noise that never becomes text (runs to the 15 s cap). If it holds, make piped the default and record the decision in the plan.

### Alternatives considered

- **ML Kit GenAI Speech Recognition** (Google's on-device model with documented file-descriptor input): rejected, alpha (`1.0.0-alpha1`).
- **Loading Google's engine library directly** (as [gasr](https://github.com/biemster/gasr) does with Chrome's `libsoda`): rejected; proprietary, guarded by an API key and caller checks, and on Android it lives inside another app's process.
- **Our own on-device recognizer**, if piped mode fails. sherpa-onnx (Apache-2.0, streaming Zipformer about 70 MB int8, LibriSpeech-trained models score 3.05 / 7.69 % WER on LibriSpeech clean/other, hotword biasing that could take contact names) vs Moonshine v2 streaming (MIT, 300K hours of training data, Small 7.84 % Open ASR average, no biasing, no phone benchmarks). Google's on-device model has no public WER; expected to be better on short commands and names because of training data matched to voice queries and built-in contextual biasing, but names already fail with it (above). Decide with a benchmark of real requests on the Pixel 8, not public numbers.

