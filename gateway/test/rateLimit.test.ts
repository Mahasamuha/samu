import assert from "node:assert/strict";
import { test } from "node:test";
import { RateLimiter } from "../src/rateLimit.js";

test("allows a burst, then refuses with a wait time", () => {
  const limiter = new RateLimiter(60, 3); // 1 token per second, burst of 3
  const now = 1_000_000;

  for (let i = 0; i < 3; i++) assert.equal(limiter.take("user", now), 0);
  assert.equal(limiter.take("user", now), 1);
  assert.equal(limiter.take("other-user", now), 0, "buckets are per key");
  assert.equal(
    limiter.take("user", now + 1000),
    0,
    "a token refills after a second",
  );
});

test("never accumulates more than the burst while idle", () => {
  const limiter = new RateLimiter(60, 2);
  limiter.take("user", 0);
  const later = 3_600_000; // an hour idle
  assert.equal(limiter.take("user", later), 0);
  assert.equal(limiter.take("user", later), 0);
  assert.equal(limiter.take("user", later), 1);
});

test("the wait time rounds up to whole seconds and shrinks as tokens refill", () => {
  const limiter = new RateLimiter(30, 1); // one token per 2 seconds
  assert.equal(limiter.take("user", 0), 0);
  assert.equal(limiter.take("user", 0), 2);
  assert.equal(limiter.take("user", 500), 2); // 1.5s left, rounded up
  assert.equal(limiter.take("user", 1500), 1);
  assert.equal(limiter.take("user", 2000), 0);
});

test("a refused request does not use up a token", () => {
  const limiter = new RateLimiter(60, 1);
  limiter.take("user", 0);
  for (let i = 0; i < 5; i++) limiter.take("user", 0);
  assert.equal(limiter.take("user", 1000), 0);
});

test("a zero burst refuses everything", () => {
  const limiter = new RateLimiter(60, 0);
  assert.ok(limiter.take("user", 0) > 0);
});
