import assert from "node:assert/strict";
import { test } from "node:test";
import { ResponseCapture } from "../src/capture.js";

const bytes = (text: string) => new TextEncoder().encode(text);

test("assembles streamed text and the final usage; raw is opt-in", () => {
  const capture = new ResponseCapture();
  const stream = [
    'event: response.output_text.delta\ndata: {"type":"response.output_text.delta","delta":"Hel"}\n\n',
    'data: {"type":"response.output_text.delta","delta":"lo"}\n\n',
    'data: {"type":"response.completed","response":{"usage":{"total_tokens":3}}}\n\n',
    "data: [DONE]\n\n",
  ].join("");
  // Split mid-line to show chunk boundaries don't matter.
  capture.add(bytes(stream.slice(0, 50)));
  capture.add(bytes(stream.slice(50)));

  assert.deepEqual(capture.summarize(false), {
    truncated: false,
    text: "Hello",
    usage: { total_tokens: 3 },
  });
  assert.equal(capture.summarize(true).raw, stream);
});

test("ignores malformed events without losing the rest", () => {
  const capture = new ResponseCapture();
  capture.add(
    bytes(
      'data: {oops\n\ndata: {"type":"response.output_text.delta","delta":"ok"}\n\n',
    ),
  );
  assert.equal(capture.summarize(false).text, "ok");
});

test("a non-stream JSON body is parsed", () => {
  const capture = new ResponseCapture();
  capture.add(bytes('{"error":{"message":"bad"}}'));
  assert.deepEqual(capture.summarize(false), {
    truncated: false,
    body: { error: { message: "bad" } },
  });
});

test("a non-JSON body is kept as truncated text", () => {
  const capture = new ResponseCapture();
  capture.add(bytes("x".repeat(20_000)));
  assert.equal((capture.summarize(false).body as string).length, 10_000);
});

test("stops collecting past the size cap and says so", () => {
  const capture = new ResponseCapture();
  capture.add(new Uint8Array(900_000));
  capture.add(new Uint8Array(200_000)); // would exceed 1,000,000: dropped
  const summary = capture.summarize(true);
  assert.equal(summary.truncated, true);
  assert.equal((summary.raw as string).length, 900_000);
});

test("an empty capture summarizes to an empty body", () => {
  assert.deepEqual(new ResponseCapture().summarize(false), {
    truncated: false,
    body: "",
  });
});
