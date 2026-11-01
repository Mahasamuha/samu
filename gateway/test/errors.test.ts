import assert from "node:assert/strict";
import { test } from "node:test";
import { ConfigError, HttpError } from "../src/errors.js";

test("HttpError carries a status, message and optional headers", () => {
  const e = new HttpError(429, "Slow down", { "retry-after": "3" });
  assert.ok(e instanceof Error);
  assert.equal(e.name, "HttpError");
  assert.equal(e.status, 429);
  assert.equal(e.message, "Slow down");
  assert.deepEqual(e.headers, { "retry-after": "3" });
  assert.deepEqual(new HttpError(404, "Not found").headers, {});
});

test("ConfigError is a distinct Error type", () => {
  const e = new ConfigError("X is required");
  assert.ok(e instanceof Error);
  assert.ok(!(e instanceof HttpError));
  assert.equal(e.name, "ConfigError");
});
