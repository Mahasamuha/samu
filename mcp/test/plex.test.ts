import assert from "node:assert/strict";
import { EventEmitter } from "node:events";
import https from "node:https";
import { syncBuiltinESMExports } from "node:module";
import { afterEach, test } from "node:test";
import { musicFind, PlexError } from "../src/plex.js";
import { FAKE_KEY, testConfig } from "./helpers.js";

// plex.ts uses node:https directly (for the TLS name check), so the tests replace https.request with a fake
// Plex server keyed by path. syncBuiltinESMExports makes the ESM named import see the replacement.
const realRequest = https.request;
afterEach(() => {
  https.request = realRequest;
  syncBuiltinESMExports();
});

interface Seen {
  url: URL;
  headers: Record<string, string>;
  options: Record<string, unknown>;
}

type Reply = { status?: number; body?: unknown; raw?: string; error?: NodeJS.ErrnoException };

function fakePlex(route: (path: string) => Reply): Seen[] {
  const seen: Seen[] = [];
  const fake = (url: URL, options: Record<string, unknown>, callback: (res: EventEmitter & { statusCode?: number }) => void) => {
    seen.push({ url, headers: options.headers as Record<string, string>, options });
    const req = new EventEmitter() as EventEmitter & { end(): void; destroy(e?: Error): void };
    req.destroy = (e) => e && req.emit("error", e);
    req.end = () => {
      const reply = route(url.pathname + url.search);
      queueMicrotask(() => {
        if (reply.error) return req.emit("error", reply.error);
        const res = new EventEmitter() as EventEmitter & { statusCode?: number };
        res.statusCode = reply.status ?? 200;
        callback(res);
        res.emit("data", Buffer.from(reply.raw ?? JSON.stringify(reply.body ?? {})));
        res.emit("end");
      });
    };
    return req;
  };
  https.request = fake as unknown as typeof https.request;
  syncBuiltinESMExports();
  return seen;
}

const config = testConfig({ plexUrl: "https://127.0.0.1:32400", plexToken: FAKE_KEY });
const sections = { MediaContainer: { Directory: [{ key: "1", type: "movie", title: "Music" }, { key: "7", type: "artist", title: "Music" }] } };

const hubs = (...hub: { type: string; Metadata: unknown[] }[]) => ({ MediaContainer: { Hub: hub } });

test("finds an album by name, with its artist", async () => {
  const seen = fakePlex((path) =>
    path === "/library/sections"
      ? { body: sections }
      : { body: hubs({ type: "album", Metadata: [{ title: "Greatest Hits", parentTitle: "Some Band" }] }) },
  );
  const result = await musicFind(config, { kind: "album", name: "greatest hits" });
  assert.deepEqual(result, { status: "found", item: { kind: "album", name: "Greatest Hits", artist: "Some Band" } });
  const search = seen.at(-1)!.url;
  assert.equal(search.pathname, "/hubs/search");
  assert.equal(search.searchParams.get("sectionId"), "7");
});

test("the token is sent in a header and appears in no URL or result", async () => {
  const seen = fakePlex((path) =>
    path === "/library/sections" ? { body: sections } : { body: hubs({ type: "artist", Metadata: [{ title: "Some Band" }] }) },
  );
  const result = await musicFind(config, { kind: "artist", name: "some band" });
  assert.equal(seen[0]!.headers["x-plex-token"], FAKE_KEY);
  for (const s of seen) assert.ok(!s.url.href.includes(FAKE_KEY));
  assert.ok(!JSON.stringify(result).includes(FAKE_KEY));
});

test("a song prefers the requested artist among same-named songs", async () => {
  fakePlex((path) =>
    path === "/library/sections"
      ? { body: sections }
      : {
          body: hubs({
            type: "track",
            Metadata: [
              { title: "Home", parentTitle: "Album One", grandparentTitle: "Band A" },
              { title: "Home", parentTitle: "Album Two", grandparentTitle: "Band B" },
            ],
          }),
        },
  );
  const result = await musicFind(config, { kind: "song", name: "Home", artist: "band b" });
  assert.deepEqual(result, { status: "found", item: { kind: "song", name: "Home", artist: "Band B", album: "Album Two" } });
});

test("punctuation and case are ignored when matching", async () => {
  fakePlex((path) =>
    path === "/library/sections"
      ? { body: sections }
      : { body: hubs({ type: "track", Metadata: [{ title: "Money, Money, Money", grandparentTitle: "Band" }] }) },
  );
  const result = await musicFind(config, { kind: "song", name: "money money money" });
  assert.equal(result.status, "found");
});

test("a short library title does not match a longer request", async () => {
  fakePlex((path) =>
    path === "/library/sections" ? { body: sections } : { body: hubs({ type: "album", Metadata: [{ title: "I", parentTitle: "X" }] }) },
  );
  const result = await musicFind(config, { kind: "album", name: "I Want It All" });
  assert.equal(result.status, "not_found");
});

test("not found returns de-duplicated, capped suggestions", async () => {
  const albums = Array.from({ length: 4 }, () => ({ title: "Other", parentTitle: "X" }));
  fakePlex((path) =>
    path === "/library/sections"
      ? { body: sections }
      : {
          body: hubs(
            { type: "album", Metadata: albums },
            { type: "track", Metadata: [1, 2, 3, 4, 5, 6].map((n) => ({ title: `Song ${n}`, grandparentTitle: "X", parentTitle: "Other" })) },
          ),
        },
  );
  const result = await musicFind(config, { kind: "album", name: "Missing" });
  assert.equal(result.status, "not_found");
  if (result.status !== "not_found") return;
  assert.equal(result.suggestions.length, 5);
  assert.equal(result.suggestions.filter((s) => s.name === "Other").length, 1);
});

test("a genre matches loosely; when nothing matches, the genres are listed", async () => {
  fakePlex((path) =>
    path === "/library/sections"
      ? { body: sections }
      : { body: { MediaContainer: { Directory: [{ title: "Rock" }, { title: "Stage & Screen" }] } } },
  );
  assert.deepEqual(await musicFind(config, { kind: "genre", name: "rock" }), { status: "found", item: { kind: "genre", name: "Rock" } });
  assert.deepEqual(await musicFind(config, { kind: "genre", name: "musical" }), {
    status: "not_found",
    suggestions: [{ kind: "genre", name: "Rock" }, { kind: "genre", name: "Stage & Screen" }],
  });
});

test("playlists come from the audio playlist listing", async () => {
  const seen = fakePlex((path) =>
    path === "/library/sections" ? { body: sections } : { body: { MediaContainer: { Metadata: [{ title: "Road Trip" }] } } },
  );
  const result = await musicFind(config, { kind: "playlist", name: "road trip" });
  assert.deepEqual(result, { status: "found", item: { kind: "playlist", name: "Road Trip" } });
  assert.equal(seen.at(-1)!.url.pathname, "/playlists");
});

test("a missing music section is a PlexError naming the section", async () => {
  fakePlex(() => ({ body: { MediaContainer: { Directory: [{ key: "1", type: "movie", title: "Music" }] } } }));
  await assert.rejects(musicFind(config, { kind: "album", name: "x" }), (e) => e instanceof PlexError && /No music library section called Music/.test(e.message));
});

test("the section name is matched without regard to case", async () => {
  fakePlex((path) => (path === "/library/sections" ? { body: sections } : { body: hubs() }));
  const result = await musicFind(testConfig({ ...config, plexMusicSection: "music" }), { kind: "album", name: "x" });
  assert.equal(result.status, "not_found");
});

test("HTTP errors, unreadable data and connection failures are PlexErrors without the token", async () => {
  fakePlex(() => ({ status: 401 }));
  await assert.rejects(musicFind(config, { kind: "album", name: "x" }), (e) => e instanceof PlexError && /Plex returned 401/.test(e.message));
  fakePlex(() => ({ raw: "<html>" }));
  await assert.rejects(musicFind(config, { kind: "album", name: "x" }), /unreadable data/);
  fakePlex(() => ({ error: Object.assign(new Error(""), { code: "ECONNREFUSED" }) }));
  await assert.rejects(musicFind(config, { kind: "album", name: "x" }), (e) => e instanceof PlexError && /ECONNREFUSED/.test(e.message) && !e.message.includes(FAKE_KEY));
});

test("with a TLS name, certificate identity is checked against it, not the URL host", async () => {
  const seen = fakePlex(() => ({ body: sections }));
  await musicFind(testConfig({ ...config, plexTlsName: "name.example.invalid" }), { kind: "playlist", name: "x" }).catch(() => undefined);
  assert.equal(typeof seen[0]!.options.checkServerIdentity, "function");
  const without = fakePlex(() => ({ body: sections }));
  await musicFind(config, { kind: "playlist", name: "x" }).catch(() => undefined);
  assert.equal(without[0]!.options.checkServerIdentity, undefined);
});
