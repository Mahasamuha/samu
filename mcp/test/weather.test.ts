import assert from "node:assert/strict";
import { test } from "node:test";
import { weather, WeatherError } from "../src/weather.js";
import { FAKE_KEY, stubFetch, testConfig } from "./helpers.js";

// "Now" is Sunday 2026-11-01 10:00 UTC in a UTC forecast zone; the daily data runs eight days from the 1st.
const HOUR = 3600;
const START = Date.UTC(2026, 10, 1) / 1000;
const NOW = START + 10 * HOUR;

const forecast = {
  timezone: "UTC",
  currently: { time: NOW, summary: "Light Rain", temperature: 50.4, apparentTemperature: 47.6, windSpeed: 7.2, humidity: 0.83 },
  minutely: { summary: "Rain stopping in 20 min." },
  hourly: {
    data: Array.from({ length: 72 }, (_, i) => ({
      time: START + i * HOUR, summary: "Cloudy", temperature: 40 + i, precipProbability: 0.25,
    })),
  },
  daily: {
    data: Array.from({ length: 8 }, (_, i) => ({
      time: START + i * 24 * HOUR, summary: "Mostly Cloudy", temperatureHigh: 60 + i, temperatureLow: 40 + i, precipProbability: 0.5,
    })),
  },
  alerts: [
    { title: "Flood Watch", severity: "Severe", expires: START + 30 * HOUR, description: "Heavy   rain\n\nexpected. " + "x".repeat(600) },
  ],
};

const springfields = {
  results: [
    { name: "Springfield", latitude: 39.8, longitude: -89.65, admin1: "Illinois", country: "United States", country_code: "US" },
    { name: "Springfield", latitude: 44.05, longitude: -123.02, admin1: "Oregon", country: "United States", country_code: "US" },
  ],
};

function route(url: URL): unknown {
  return url.hostname.startsWith("geocoding") ? springfields : forecast;
}

test("current conditions: now, next hour, today, remaining hours, alerts", async () => {
  stubFetch(route);
  const r = (await weather(testConfig(), { place: "Springfield" })) as Record<string, any>;
  assert.equal(r.place, "Springfield, Illinois");
  assert.deepEqual(r.now, { temperature: "50°F", feels_like: "48°F", condition: "light rain", wind: "7 mph", humidity: "83%" });
  assert.equal(r.next_hour, "Rain stopping in 20 min.");
  assert.deepEqual(r.today, { date: "Sunday, November 1", high: "60°F", low: "40°F", condition: "mostly cloudy", precipitation_chance: "50%" });
  // Every third hour after now, through 6 AM tomorrow.
  assert.deepEqual(r.hours.map((h: any) => h.time), [
    "Sunday 12 PM", "Sunday 3 PM", "Sunday 6 PM", "Sunday 9 PM", "Monday 12 AM", "Monday 3 AM", "Monday 6 AM",
  ]);
  assert.equal(r.hours[0].precipitation_chance, "25%");
  assert.equal(r.alerts.length, 1);
  assert.equal(r.alerts[0].title, "Flood Watch");
  assert.equal(r.alerts[0].severity, "Severe");
  assert.equal(r.alerts[0].until, "Monday 6 AM");
  assert.ok(r.alerts[0].description.startsWith("Heavy rain expected. "));
  assert.equal(r.alerts[0].description.length, 400);
});

test("no alerts key when there are none; next_hour omitted without minutely data", async () => {
  stubFetch((url) => (url.hostname.startsWith("geocoding") ? springfields : { ...forecast, alerts: undefined, minutely: undefined }));
  const r = await weather(testConfig(), { place: "Springfield" });
  assert.ok(!("alerts" in r));
  assert.ok(!("next_hour" in r));
});

test("one day: its summary and hours for that date only", async () => {
  stubFetch(route);
  const r = (await weather(testConfig(), { place: "Springfield", day: "2026-11-03" })) as Record<string, any>;
  assert.equal(r.day.date, "Tuesday, November 3");
  assert.equal(r.day.high, "62°F");
  // Hours exist only for the 3rd (index 48..71 holds the 3rd), at every third hour, labelled without the day.
  assert.equal(r.hours.length, 8);
  assert.equal(r.hours[0].time, "12 AM");
  assert.equal(r.hours[7].time, "9 PM");
  assert.ok(!("now" in r));
});

test("a range gives one summary per day, inclusive", async () => {
  stubFetch(route);
  const r = (await weather(testConfig(), { place: "Springfield", day: "2026-11-02", endDay: "2026-11-04" })) as Record<string, any>;
  assert.deepEqual(r.days.map((d: any) => d.date), ["Monday, November 2", "Tuesday, November 3", "Wednesday, November 4"]);
  assert.ok(!("hours" in r));
});

test("end_day equal to day is a single-day request", async () => {
  stubFetch(route);
  const r = (await weather(testConfig(), { place: "Springfield", day: "2026-11-02", endDay: "2026-11-02" })) as Record<string, any>;
  assert.ok("day" in r);
  assert.ok(!("days" in r));
});

test("units change the labels", async () => {
  stubFetch(route);
  const r = (await weather(testConfig({ weatherUnits: "ca" }), { place: "Springfield" })) as Record<string, any>;
  assert.equal(r.now.temperature, "50°C");
  assert.equal(r.now.wind, "7 km/h");
});

test("the API key goes in a header, not in any URL; units are requested", async () => {
  const calls = stubFetch(route);
  await weather(testConfig({ weatherUnits: "si" }), { place: "Springfield" });
  const forecastCall = calls.find((c) => c.url.hostname.includes("pirateweather"));
  assert.ok(forecastCall);
  assert.equal(forecastCall.headers.apikey, FAKE_KEY);
  assert.equal(forecastCall.url.searchParams.get("units"), "si");
  for (const c of calls) {
    assert.ok(!String(c.url).includes(FAKE_KEY));
    if (c !== forecastCall) assert.equal(c.headers.apikey, undefined);
  }
});

const invalid: [string, { day?: string; endDay?: string }, RegExp][] = [
  ["end_day without day", { endDay: "2026-11-03" }, /end_day needs day/],
  ["end_day before day", { day: "2026-11-03", endDay: "2026-11-02" }, /end_day must come after day/],
  ["bad day format", { day: "11/03/2026" }, /day must be a date like/],
  ["impossible day", { day: "2026-13-45" }, /day must be a date like/],
  ["bad end_day format", { day: "2026-11-03", endDay: "tomorrow" }, /end_day must be a date like/],
];
for (const [name, args, message] of invalid) {
  test(`rejects ${name} before any network call`, async () => {
    const calls = stubFetch(route);
    await assert.rejects(weather(testConfig(), { place: "Springfield", ...args }), (e) => e instanceof WeatherError && message.test(e.message));
    assert.equal(calls.length, 0);
  });
}

test("rejects a past day", async () => {
  stubFetch(route);
  await assert.rejects(weather(testConfig(), { place: "Springfield", day: "2026-10-31" }), /not past weather/);
});

test("rejects a day or range end beyond the forecast", async () => {
  stubFetch(route);
  await assert.rejects(weather(testConfig(), { place: "Springfield", day: "2026-11-09" }), /forecast only reaches Sunday, November 8/);
  await assert.rejects(weather(testConfig(), { place: "Springfield", day: "2026-11-07", endDay: "2026-11-10" }), /only reaches/);
  await weather(testConfig(), { place: "Springfield", day: "2026-11-08" });
});

test("unknown place is a WeatherError", async () => {
  stubFetch((url) => (url.hostname.startsWith("geocoding") ? {} : forecast));
  await assert.rejects(weather(testConfig(), { place: "Nowhereville" }), (e) => e instanceof WeatherError && /Couldn't find a place called Nowhereville/.test(e.message));
});

test("a qualifier picks the matching place, by name or state abbreviation", async () => {
  stubFetch(route);
  assert.equal(((await weather(testConfig(), { place: "Springfield, OR" })) as any).place, "Springfield, Oregon");
  assert.equal(((await weather(testConfig(), { place: "Springfield, Illinois, USA" })) as any).place, "Springfield, Illinois");
});

test("a qualifier that matches nothing is not answered with the wrong place", async () => {
  stubFetch(route);
  await assert.rejects(weather(testConfig(), { place: "Springfield, Texas" }), /Couldn't find a place/);
});

test("geocoding and forecast failures are WeatherErrors", async () => {
  stubFetch((url) => (url.hostname.startsWith("geocoding") ? new Response("", { status: 500 }) : forecast));
  await assert.rejects(weather(testConfig(), { place: "Springfield" }), /Place lookup returned 500/);
  stubFetch((url) => (url.hostname.startsWith("geocoding") ? springfields : new Error("boom")));
  await assert.rejects(weather(testConfig(), { place: "Springfield" }), (e) => e instanceof WeatherError && /Weather service unreachable/.test(e.message));
});
