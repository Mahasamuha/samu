import assert from "node:assert/strict";
import { mkdtemp, readdir, readFile, rm, utimes, writeFile } from "node:fs/promises";
import { tmpdir } from "node:os";
import path from "node:path";
import { afterEach, beforeEach, describe, test, type TestContext } from "node:test";
import { BodyLog, DebugSwitch, log } from "../src/logging.js";

const sleep = (ms: number) => new Promise((resolve) => setTimeout(resolve, ms));

/** Collects what `log` writes to console.log, parsed. */
function captureLogs(t: TestContext): Array<Record<string, unknown>> {
  const lines: Array<Record<string, unknown>> = [];
  t.mock.method(console, "log", (line: string) => void lines.push(JSON.parse(line) as Record<string, unknown>));
  return lines;
}

test("log writes one JSON line with a timestamp and the event", (t) => {
  const lines = captureLogs(t);
  log("something", { a: 1 });
  assert.equal(lines.length, 1);
  assert.equal(lines[0]?.event, "something");
  assert.equal(lines[0]?.a, 1);
  assert.ok(!Number.isNaN(Date.parse(String(lines[0]?.time))));
});

describe("DebugSwitch", () => {
  test("starts off, toggles on and off", (t) => {
    const lines = captureLogs(t);
    const debug = new DebugSwitch(60_000);
    assert.equal(debug.on, false);
    debug.toggle();
    assert.equal(debug.on, true);
    debug.toggle();
    assert.equal(debug.on, false);
    assert.deepEqual(lines.map((l) => l.event), ["debug_on", "debug_off"]);
    assert.equal(lines[1]?.reason, "toggled");
  });

  test("switches itself off after its duration", async (t) => {
    const lines = captureLogs(t);
    const debug = new DebugSwitch(40);
    debug.toggle();
    assert.equal(debug.on, true);
    await sleep(150);
    assert.equal(debug.on, false);
    assert.equal(lines.at(-1)?.event, "debug_off");
    assert.equal(lines.at(-1)?.reason, "expired");
  });

  test("a manual off cancels the timer, so a later session gets its full duration", async (t) => {
    captureLogs(t);
    const debug = new DebugSwitch(120);
    debug.toggle();
    debug.toggle();
    await sleep(30);
    debug.toggle(); // on again
    await sleep(100); // past the first timer's deadline, before the second's
    assert.equal(debug.on, true);
    await sleep(100);
    assert.equal(debug.on, false);
  });
});

describe("BodyLog", () => {
  let dir: string;
  beforeEach(async () => {
    dir = await mkdtemp(path.join(tmpdir(), "bodylog-test-"));
  });
  afterEach(() => rm(dir, { recursive: true, force: true }));

  const hoursAgo = (h: number) => new Date(Date.now() - h * 3_600_000);

  test("is enabled by retention, or by debug mode when retention is 0", (t) => {
    captureLogs(t);
    const debug = new DebugSwitch(60_000);
    assert.equal(new BodyLog(dir, 3_600_000, debug, 60_000).enabled, true);
    const off = new BodyLog(dir, 0, debug, 60_000);
    assert.equal(off.enabled, false);
    debug.toggle();
    assert.equal(off.enabled, true);
    debug.toggle();
    assert.equal(off.enabled, false);
  });

  test("init creates the directory; write appends JSONL records with owner-only permissions", async () => {
    const nested = path.join(dir, "a", "b");
    const bodyLog = new BodyLog(nested, 3_600_000, new DebugSwitch(1000), 1000);
    await bodyLog.init();
    await bodyLog.write({ n: 1 });
    await bodyLog.write({ n: 2 });

    const files = await readdir(nested);
    assert.equal(files.length, 1);
    assert.match(files[0]!, /^bodies-\d{4}-\d{2}-\d{2}T\d{2}\.jsonl$/);
    const lines = (await readFile(path.join(nested, files[0]!), "utf8")).trim().split("\n");
    assert.deepEqual(lines.map((l) => JSON.parse(l)), [{ n: 1 }, { n: 2 }]);

    const { mode } = await (await import("node:fs/promises")).stat(path.join(nested, files[0]!));
    assert.equal(mode & 0o077, 0, "no group/other access");
  });

  async function seed(): Promise<void> {
    for (const [name, age] of [["bodies-old.jsonl", 30], ["bodies-recent.jsonl", 2], ["bodies-fresh.jsonl", 0], ["other.log", 100]] as const) {
      const file = path.join(dir, name);
      await writeFile(file, "{}\n");
      await utimes(file, hoursAgo(age), hoursAgo(age));
    }
  }

  test("init prunes body files older than the retention only", async () => {
    await seed();
    await new BodyLog(dir, 24 * 3_600_000, new DebugSwitch(1000), 1000).init();
    assert.deepEqual((await readdir(dir)).sort(), ["bodies-fresh.jsonl", "bodies-recent.jsonl", "other.log"]);
  });

  test("with retention 0, files are kept for one debug duration", async () => {
    await seed();
    await new BodyLog(dir, 0, new DebugSwitch(1000), 3 * 3_600_000).init();
    assert.deepEqual((await readdir(dir)).sort(), ["bodies-fresh.jsonl", "bodies-recent.jsonl", "other.log"]);
  });
});
