import { request } from "node:https";
import { checkServerIdentity } from "node:tls";
import type { Config } from "./config.js";

/**
 * `music_find` backed by the user's Plex music library: turns what the user said into the exact names
 * the library uses, which the phone's music player app then matches by exact name.
 */

export class PlexError extends Error {}

export type MusicKind = "album" | "song" | "artist" | "playlist" | "genre";

export interface MusicRequest {
  kind: MusicKind;
  name: string;
  artist?: string;
}

export interface MusicItem {
  kind: MusicKind;
  name: string;
  artist?: string;
  album?: string;
}

export type MusicResult =
  | { status: "found"; item: MusicItem }
  | { status: "not_found"; suggestions: MusicItem[] };

interface Entry {
  type?: string;
  title: string;
  /** Albums: the artist. Tracks: the album. */
  parentTitle?: string;
  /** Tracks: the artist. */
  grandparentTitle?: string;
}

interface Container {
  MediaContainer?: {
    Directory?: Entry[];
    Metadata?: Entry[];
    Hub?: { type?: string; Metadata?: Entry[] }[];
  };
}

const HUB_KINDS = { album: "album", song: "track", artist: "artist" } as const;
const MAX_SUGGESTIONS = 5;
/** Genres and playlists listed when none matches. */
const MAX_LISTED = 40;

export async function musicFind(
  config: Config,
  req: MusicRequest,
): Promise<MusicResult> {
  const section = await musicSection(config);
  if (req.kind === "genre" || req.kind === "playlist") {
    const json =
      req.kind === "genre"
        ? await get(config, `/library/sections/${section}/genre`)
        : await get(config, "/playlists?playlistType=audio");
    const titles = (
      json.MediaContainer?.Directory ??
      json.MediaContainer?.Metadata ??
      []
    ).map((entry) => entry.title);
    const match = bestTitle(titles, req.name);
    if (match)
      return { status: "found", item: { kind: req.kind, name: match } };
    // A library has few genres and playlists, and the right one often shares no word with the request
    // ("musical" is "Stage & Screen"), so all of them go back for the model to choose from.
    return {
      status: "not_found",
      suggestions: titles
        .slice(0, MAX_LISTED)
        .map((name) => ({ kind: req.kind, name })),
    };
  }

  const json = await get(
    config,
    `/hubs/search?query=${encodeURIComponent(req.name)}&sectionId=${section}&limit=10`,
  );
  const hubs = json.MediaContainer?.Hub ?? [];
  const items = (hubKind: string, kind: MusicKind): MusicItem[] =>
    hubs
      .filter((hub) => hub.type === hubKind)
      .flatMap((hub) => hub.Metadata ?? [])
      .map((entry) => toItem(kind, entry));

  const candidates = items(HUB_KINDS[req.kind], req.kind);
  const named = candidates.filter(
    (item) => normalize(item.name) === normalize(req.name),
  );
  // Only titles that contain the request: the reverse would let a short title ("I") match a longer request.
  const pool =
    named.length > 0
      ? named
      : candidates.filter((item) =>
          normalize(item.name).includes(normalize(req.name)),
        );
  const wantedArtist = req.artist ? normalize(req.artist) : undefined;
  const byArtist = wantedArtist
    ? pool.filter(
        (item) =>
          item.artist !== undefined &&
          (normalize(item.artist) === wantedArtist ||
            matches(item.artist, wantedArtist)),
      )
    : [];
  // An artist that fits beats a name that fits; the exact artist beats a partial one.
  const exactArtist = byArtist.filter(
    (item) => normalize(item.artist ?? "") === wantedArtist,
  );
  const found = exactArtist[0] ?? byArtist[0] ?? pool[0];
  if (found) return { status: "found", item: found };

  // The library can hold the same album twice (editions), so repeats are dropped.
  const seen = new Set<string>();
  const suggestions = [
    items("album", "album"),
    items("track", "song"),
    items("artist", "artist"),
  ]
    .flat()
    .filter((item) => {
      const key = `${item.kind}|${item.name}|${item.artist ?? ""}`;
      return !seen.has(key) && seen.add(key);
    });
  return {
    status: "not_found",
    suggestions: suggestions.slice(0, MAX_SUGGESTIONS),
  };
}

function toItem(kind: MusicKind, entry: Entry): MusicItem {
  if (kind === "song") {
    return {
      kind,
      name: entry.title,
      ...(entry.grandparentTitle ? { artist: entry.grandparentTitle } : {}),
      ...(entry.parentTitle ? { album: entry.parentTitle } : {}),
    };
  }
  if (kind === "album")
    return {
      kind,
      name: entry.title,
      ...(entry.parentTitle ? { artist: entry.parentTitle } : {}),
    };
  return { kind, name: entry.title };
}

/**
 * Lowercase, without punctuation, single-spaced, so "Don’t" matches "don't" and "money money money"
 * matches "Money, Money, Money" (speech recognition writes no punctuation).
 */
function normalize(value: string): string {
  return value
    .replace(/[‘’‛]/g, "'")
    .toLowerCase()
    .replace(/[^\p{L}\p{N}' ]+/gu, " ")
    .trim()
    .replace(/\s+/g, " ");
}

function matches(title: string, name: string): boolean {
  const a = normalize(title);
  const b = normalize(name);
  return a.includes(b) || b.includes(a);
}

/** The title that equals the name, else the first that contains it or is contained by it. */
function bestTitle(titles: string[], name: string): string | undefined {
  return (
    titles.find((title) => normalize(title) === normalize(name)) ??
    titles.find((title) => matches(title, name))
  );
}

/** The key of the library section named in the config. */
async function musicSection(config: Config): Promise<string> {
  const json = (await get(config, "/library/sections")) as {
    MediaContainer?: { Directory?: (Entry & { key: string })[] };
  };
  const wanted = normalize(config.plexMusicSection);
  const section = json.MediaContainer?.Directory?.find(
    (entry) => entry.type === "artist" && normalize(entry.title) === wanted,
  );
  if (!section)
    throw new PlexError(
      `No music library section called ${config.plexMusicSection}`,
    );
  return section.key;
}

/**
 * GET against the Plex server. Plex serves a certificate issued for a *.plex.direct name, not the LAN
 * host, so with PLEX_TLS_NAME the certificate is checked against that name instead of the URL's host.
 * The CA chain is always verified. The token travels in a header and appears in no message or log.
 */
function get(config: Config, path: string): Promise<Container> {
  return new Promise((resolve, reject) => {
    const tlsName = config.plexTlsName;
    const req = request(
      new URL(path, config.plexUrl),
      {
        method: "GET",
        headers: {
          accept: "application/json",
          "x-plex-token": config.plexToken ?? "",
          "x-plex-product": "Samu",
        },
        timeout: 5_000,
        ...(tlsName
          ? {
              checkServerIdentity: (
                _host: string,
                cert: Parameters<typeof checkServerIdentity>[1],
              ) => checkServerIdentity(tlsName, cert),
            }
          : {}),
      },
      (res) => {
        const chunks: Buffer[] = [];
        res.on("data", (chunk: Buffer) => chunks.push(chunk));
        res.on("error", (e) =>
          reject(new PlexError(`Plex unreachable: ${e.message}`)),
        );
        res.on("end", () => {
          if (
            !res.statusCode ||
            res.statusCode < 200 ||
            res.statusCode >= 300
          ) {
            return reject(new PlexError(`Plex returned ${res.statusCode}`));
          }
          try {
            resolve(
              JSON.parse(Buffer.concat(chunks).toString("utf8")) as Container,
            );
          } catch {
            reject(new PlexError("Plex returned unreadable data"));
          }
        });
      },
    );
    req.on("timeout", () => req.destroy(new Error("timed out")));
    // A failed connection to every address is an AggregateError with an empty message; the code says why.
    req.on("error", (e: NodeJS.ErrnoException) =>
      reject(new PlexError(`Plex unreachable: ${e.code ?? e.message}`)),
    );
    req.end();
  });
}
