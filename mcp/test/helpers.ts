import { afterEach, mock } from "node:test";
import type { Config } from "../src/config.js";

// Stubs made with the global mock are undone after every test in the importing file.
afterEach(() => mock.restoreAll());

export const FAKE_KEY = "test-key-do-not-leak";

export function testConfig(overrides: Partial<Config> = {}): Config {
  return {
    version: "test",
    port: 0,
    braveApiKey: FAKE_KEY,
    searchCountry: "us",
    searchMaxChars: 1000,
    searchMaxPages: 3,
    pirateWeatherApiKey: FAKE_KEY,
    weatherUnits: "us",
    plexUrl: undefined,
    plexToken: undefined,
    plexTlsName: undefined,
    plexMusicSection: "Music",
    maxBodyBytes: 64 * 1024,
    ...overrides,
  };
}

export interface FetchCall {
  url: URL;
  headers: Record<string, string>;
}

/** A Response, an Error (a network failure), or any JSON-able value. */
type Reply = unknown;

/**
 * Replaces globalThis.fetch for the current test. `route` receives each request URL and returns a JSON-able
 * value (200), a Response, or an Error (thrown as a network failure). The mock is restored by the test context.
 */
export function stubFetch(route: (url: URL) => Reply): FetchCall[] {
  const calls: FetchCall[] = [];
  mock.method(
    globalThis,
    "fetch",
    (input: URL | string, init?: RequestInit) => {
      const url = new URL(String(input));
      calls.push({
        url,
        headers: { ...(init?.headers as Record<string, string> | undefined) },
      });
      const reply = route(url);
      if (reply instanceof Error) return Promise.reject(reply);
      if (reply instanceof Response) return Promise.resolve(reply);
      return Promise.resolve(
        new Response(JSON.stringify(reply), {
          status: 200,
          headers: { "content-type": "application/json" },
        }),
      );
    },
  );
  return calls;
}

/** Silences the server's JSON log lines for the current test. */
export function silenceLogs(): void {
  mock.method(console, "log", () => undefined);
}
