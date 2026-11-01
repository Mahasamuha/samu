import assert from "node:assert/strict";
import { test } from "node:test";
import { Client } from "@modelcontextprotocol/sdk/client/index.js";
import { InMemoryTransport } from "@modelcontextprotocol/sdk/inMemory.js";
import { McpServer } from "@modelcontextprotocol/sdk/server/mcp.js";
import { z } from "zod";

// app/src/main/java/com/mahasamuha/assistant/remote/McpClient.kt (SDK_INVALID_PARAMS_PREFIX) relies on this:
// the SDK reports an unknown tool or invalid arguments as an isError result whose text starts with
// "MCP error -32602:", which the phone treats as a stale capability index. A handler's own failure must not.
const INVALID_PARAMS_PREFIX = "MCP error -32602:";

async function connect(): Promise<Client> {
  const server = new McpServer({ name: "test", version: "0" });
  server.registerTool("echo", { inputSchema: { text: z.string() } }, async ({ text }) => ({
    content: [{ type: "text", text }],
  }));
  server.registerTool("boom", {}, async () => {
    throw new Error("upstream failed");
  });

  const [clientSide, serverSide] = InMemoryTransport.createLinkedPair();
  await server.connect(serverSide);
  const client = new Client({ name: "test-client", version: "0" });
  await client.connect(clientSide);
  return client;
}

function firstText(result: Awaited<ReturnType<Client["callTool"]>>): string {
  const content = result.content as { type: string; text?: string }[];
  return content[0]?.text ?? "";
}

test("unknown tool is isError with the invalid-params prefix", async () => {
  const client = await connect();
  const result = await client.callTool({ name: "nope", arguments: {} });
  assert.equal(result.isError, true);
  assert.ok(firstText(result).startsWith(INVALID_PARAMS_PREFIX), firstText(result));
});

test("missing required argument is isError with the invalid-params prefix", async () => {
  const client = await connect();
  const result = await client.callTool({ name: "echo", arguments: {} });
  assert.equal(result.isError, true);
  assert.ok(firstText(result).startsWith(INVALID_PARAMS_PREFIX), firstText(result));
});

test("a handler that throws is isError without the prefix", async () => {
  const client = await connect();
  const result = await client.callTool({ name: "boom", arguments: {} });
  assert.equal(result.isError, true);
  assert.ok(!firstText(result).startsWith(INVALID_PARAMS_PREFIX), firstText(result));
});
