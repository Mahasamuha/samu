import assert from "node:assert/strict";
import { test } from "node:test";
import { ConfigError, loadConfig } from "../src/config.js";

const minimal = { BRAVE_API_KEY: "k" };

test("defaults apply when only the required key is set", () => {
  const c = loadConfig(minimal);
  assert.equal(c.version, "dev");
  assert.equal(c.port, 8080);
  assert.equal(c.searchCountry, "us");
  assert.equal(c.searchMaxChars, 24_000);
  assert.equal(c.searchMaxPages, 8);
  assert.equal(c.weatherUnits, "us");
  assert.equal(c.plexMusicSection, "Music");
  assert.equal(c.maxBodyBytes, 64 * 1024);
  assert.equal(c.pirateWeatherApiKey, undefined);
  assert.equal(c.plexUrl, undefined);
  assert.equal(c.plexToken, undefined);
  assert.equal(c.plexTlsName, undefined);
});

test("BRAVE_API_KEY is required", () => {
  assert.throws(() => loadConfig({}), ConfigError);
  assert.throws(() => loadConfig({ BRAVE_API_KEY: "" }), /BRAVE_API_KEY is required/);
});

test("optional values are read, and empty strings count as unset", () => {
  const c = loadConfig({
    ...minimal,
    SAMU_VERSION: "1.2.3",
    PORT: "9000",
    SEARCH_COUNTRY: "gb",
    PIRATE_WEATHER_API_KEY: "pw",
    PLEX_URL: "https://127.0.0.1:32400",
    PLEX_TOKEN: "t",
    PLEX_TLS_NAME: "name.example.invalid",
    PLEX_MUSIC_SECTION: "Tunes",
  });
  assert.equal(c.version, "1.2.3");
  assert.equal(c.port, 9000);
  assert.equal(c.searchCountry, "gb");
  assert.equal(c.pirateWeatherApiKey, "pw");
  assert.equal(c.plexUrl, "https://127.0.0.1:32400");
  assert.equal(c.plexTlsName, "name.example.invalid");
  assert.equal(c.plexMusicSection, "Tunes");

  const empty = loadConfig({ ...minimal, PIRATE_WEATHER_API_KEY: "", PLEX_URL: "", PORT: "", WEATHER_UNITS: "" });
  assert.equal(empty.pirateWeatherApiKey, undefined);
  assert.equal(empty.plexUrl, undefined);
  assert.equal(empty.port, 8080);
  assert.equal(empty.weatherUnits, "us");
});

test("WEATHER_UNITS accepts us, si, ca, uk only", () => {
  for (const u of ["us", "si", "ca", "uk"]) assert.equal(loadConfig({ ...minimal, WEATHER_UNITS: u }).weatherUnits, u);
  assert.throws(() => loadConfig({ ...minimal, WEATHER_UNITS: "metric" }), /WEATHER_UNITS must be us, si, ca or uk/);
});

test("integer settings must be non-negative integers", () => {
  assert.equal(loadConfig({ ...minimal, SEARCH_MAX_PAGES: "0" }).searchMaxPages, 0);
  for (const bad of ["-1", "1.5", "abc"]) {
    assert.throws(() => loadConfig({ ...minimal, PORT: bad }), /PORT must be a non-negative integer/, bad);
  }
  assert.throws(() => loadConfig({ ...minimal, MAX_BODY_BYTES: "1e400" }), ConfigError);
});
