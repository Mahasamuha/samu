import assert from "node:assert/strict";
import { test } from "node:test";
import { search, SearchError } from "../src/brave.js";
import { FAKE_KEY, stubFetch, testConfig } from "./helpers.js";

const payload = {
  grounding: {
    generic: [
      { url: "https://a.example.invalid/1", title: "A", snippets: ["one", "two"] },
      { url: "https://b.example.invalid/2", title: "B", snippets: [] },
      { url: "https://c.example.invalid/3", title: "C", snippets: ["three"] },
      { url: "https://d.example.invalid/4", title: "D", snippets: ["four"] },
      { url: "https://e.example.invalid/5", title: "E", snippets: ["five"] },
    ],
  },
  sources: { "https://a.example.invalid/1": { age: ["Monday", "2026-10-30", "2 days ago"] } },
};

test("shapes results: joins snippets, adds ISO date, skips empty pages, caps page count", async () => {
  stubFetch(() => payload);
  const results = await search(testConfig({ searchMaxPages: 3 }), "q");
  assert.deepEqual(results, [
    { title: "A", url: "https://a.example.invalid/1", date: "2026-10-30", text: "one\n…\ntwo" },
    { title: "C", url: "https://c.example.invalid/3", text: "three" },
    { title: "D", url: "https://d.example.invalid/4", text: "four" },
  ]);
});

test("each page gets an equal share of the text budget", async () => {
  stubFetch(() => ({ grounding: { generic: [{ url: "u", title: "T", snippets: ["x".repeat(500)] }] } }));
  const [r] = await search(testConfig({ searchMaxChars: 100, searchMaxPages: 4 }), "q");
  assert.equal(r?.text.length, 25);
});

test("empty or missing grounding gives no results", async () => {
  stubFetch(() => ({}));
  assert.deepEqual(await search(testConfig(), "q"), []);
});

test("sends the query and country, and the key in a header only", async () => {
  const calls = stubFetch(() => payload);
  await search(testConfig({ searchCountry: "gb" }), "rain in Springfield");
  const [call] = calls;
  assert.equal(call?.url.searchParams.get("q"), "rain in Springfield");
  assert.equal(call?.url.searchParams.get("country"), "gb");
  assert.equal(call?.headers["x-subscription-token"], FAKE_KEY);
  assert.ok(!String(call?.url).includes(FAKE_KEY));
});

test("a non-OK status becomes a SearchError", async () => {
  stubFetch(() => new Response("nope", { status: 429 }));
  await assert.rejects(search(testConfig(), "q"), (e) => e instanceof SearchError && /429/.test(e.message));
});

test("a network failure becomes a SearchError that does not carry the key", async () => {
  stubFetch(() => new TypeError("fetch failed"));
  await assert.rejects(search(testConfig(), "q"), (e) => e instanceof SearchError && /unreachable/.test(e.message) && !e.message.includes(FAKE_KEY));
});
