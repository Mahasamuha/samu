import assert from "node:assert/strict";
import { mkdir, mkdtemp, rm, writeFile } from "node:fs/promises";
import { tmpdir } from "node:os";
import { join } from "node:path";
import { after, test } from "node:test";
import { McpServer } from "@modelcontextprotocol/sdk/server/mcp.js";
import { z } from "zod";
import {
  buildIndex,
  loadCatalog,
  type CapabilityCatalog,
} from "../src/capabilities.js";

const dirs: string[] = [];
after(async () => {
  await Promise.all(dirs.map((d) => rm(d, { recursive: true, force: true })));
});

async function tempDir(): Promise<string> {
  const dir = await mkdtemp(join(tmpdir(), "mcp-caps-"));
  dirs.push(dir);
  return dir;
}

type Tool = {
  name: string;
  where: "local" | "remote" | "hybrid";
  required?: boolean;
};
const cap = (
  id: string,
  tools: Tool[],
  extra: Record<string, unknown> = {},
) => ({
  id,
  title: id.toUpperCase(),
  description: `About ${id}`,
  load: "lazy",
  tools,
  ...extra,
});

/** Writes a manifest and skills (name -> SKILL.md text) and returns the env that points loadCatalog at them. */
async function fixture(
  manifest: unknown,
  skills: Record<string, string> = {},
): Promise<NodeJS.ProcessEnv> {
  const dir = await tempDir();
  const manifestPath = join(dir, "capabilities.json");
  await writeFile(
    manifestPath,
    typeof manifest === "string" ? manifest : JSON.stringify(manifest),
  );
  const skillsDir = join(dir, "skills");
  await mkdir(skillsDir);
  for (const [name, text] of Object.entries(skills)) {
    await mkdir(join(skillsDir, name));
    await writeFile(join(skillsDir, name, "SKILL.md"), text);
  }
  return { CAPABILITIES_FILE: manifestPath, SKILLS_DIR: skillsDir };
}

const skillText = (frontmatter: string, body = "Do the thing.") =>
  `---\n${frontmatter}\n---\n${body}\n`;

const remoteCap = cap("web", [{ name: "web_search", where: "remote" }]);

test("a valid manifest loads; local-only capabilities stay with the phone", async () => {
  const env = await fixture({
    capabilities: [
      cap("alpha", [{ name: "a", where: "local" }]),
      remoteCap,
      cap("mixed", [
        { name: "m", where: "hybrid" },
        { name: "l", where: "local" },
      ]),
    ],
  });
  const catalog = loadCatalog(env);
  assert.deepEqual(
    catalog.capabilities.map((c) => c.id),
    ["web", "mixed"],
  );
  assert.deepEqual(catalog.skills, []);
});

test("an invalid manifest throws and names the file", async () => {
  const env = await fixture({
    capabilities: [
      { id: "x", title: "X", description: "d", load: "sometimes", tools: [] },
    ],
  });
  assert.throws(
    () => loadCatalog(env),
    /Invalid capability manifest .*capabilities\.json/,
  );
});

test("a manifest that is not JSON throws", async () => {
  const env = await fixture("{nope");
  assert.throws(() => loadCatalog(env), SyntaxError);
});

test("a duplicate id throws", async () => {
  const env = await fixture({ capabilities: [remoteCap, remoteCap] });
  assert.throws(() => loadCatalog(env), /duplicate id/);
});

test("a missing manifest file throws", () => {
  assert.throws(
    () =>
      loadCatalog({
        CAPABILITIES_FILE: "/nonexistent/capabilities.json",
        SKILLS_DIR: "/nonexistent",
      }),
    /ENOENT/,
  );
});

test("server-delivery skills load with their fields; app-delivery skills are ignored", async () => {
  const env = await fixture(
    { capabilities: [remoteCap] },
    {
      b: skillText(
        "name: b-skill\ndescription: Second one\ndelivery: server\ncapabilities: [web]",
        "Use it well.\n",
      ),
      a: skillText(
        "name: a-skill\ndescription: First one # trailing comment\ndelivery: server\ncapabilities: [web, other]",
      ),
      app: skillText(
        "name: app-skill\ndescription: In the APK\ndelivery: app\ncapabilities: [web]",
      ),
      plain: skillText(
        "name: plain\ndescription: No delivery field\ncapabilities: [web]",
      ),
    },
  );
  const { skills } = loadCatalog(env);
  assert.deepEqual(skills, [
    {
      name: "a-skill",
      description: "First one",
      capabilities: ["web", "other"],
      instructions: "Do the thing.",
    },
    {
      name: "b-skill",
      description: "Second one",
      capabilities: ["web"],
      instructions: "Use it well.",
    },
  ]);
});

test("a server skill with no served capability is dropped", async () => {
  const env = await fixture(
    {
      capabilities: [remoteCap, cap("phone", [{ name: "p", where: "local" }])],
    },
    {
      s: skillText(
        "name: s\ndescription: d\ndelivery: server\ncapabilities: [phone]",
      ),
    },
  );
  assert.deepEqual(loadCatalog(env).skills, []);
});

test("CRLF line endings parse", async () => {
  const env = await fixture(
    { capabilities: [remoteCap] },
    {
      s: "---\r\nname: s\r\ndescription: d\r\ndelivery: server\r\ncapabilities: [web]\r\n---\r\nBody\r\n",
    },
  );
  assert.equal(loadCatalog(env).skills[0]?.name, "s");
});

test("a skill without frontmatter throws", async () => {
  const env = await fixture(
    { capabilities: [remoteCap] },
    { s: "just text\n" },
  );
  assert.throws(() => loadCatalog(env), /missing frontmatter/);
});

test("a server skill without name or description throws", async () => {
  const noName = await fixture(
    { capabilities: [remoteCap] },
    { s: skillText("description: d\ndelivery: server") },
  );
  assert.throws(() => loadCatalog(noName), /name and description are required/);
  const noDescription = await fixture(
    { capabilities: [remoteCap] },
    { s: skillText("name: s\ndelivery: server") },
  );
  assert.throws(
    () => loadCatalog(noDescription),
    /name and description are required/,
  );
});

test("a skill with an empty capabilities list loads but is never served", async () => {
  const env = await fixture(
    { capabilities: [remoteCap] },
    {
      s: skillText(
        "name: s\ndescription: d\ndelivery: server\ncapabilities: []",
      ),
    },
  );
  assert.deepEqual(loadCatalog(env).skills, []);
});

test("a missing skills directory means no skills", async () => {
  const env = await fixture({ capabilities: [remoteCap] });
  assert.deepEqual(
    loadCatalog({ ...env, SKILLS_DIR: "/nonexistent" }).skills,
    [],
  );
});

test("the repo's real manifest and skills load cleanly", () => {
  const root = new URL("../../../", import.meta.url).pathname;
  const catalog = loadCatalog({
    CAPABILITIES_FILE: join(root, "capabilities/capabilities.json"),
    SKILLS_DIR: join(root, "skills"),
  });
  assert.ok(catalog.capabilities.length > 0);
  for (const skill of catalog.skills)
    assert.ok(skill.instructions.length > 0, skill.name);
});

// buildIndex

function stubServer(tools: Record<string, Record<string, unknown>>): McpServer {
  const server = new McpServer({ name: "t", version: "0" });
  for (const [name, config] of Object.entries(tools)) {
    server.registerTool(
      name,
      { inputSchema: { q: z.string() }, ...config },
      async () => ({ content: [] }),
    );
  }
  return server;
}

const catalogOf = (
  capabilities: CapabilityCatalog["capabilities"],
  skills: CapabilityCatalog["skills"] = [],
): CapabilityCatalog => ({ capabilities, skills });
const asCaps = (...c: unknown[]) => c as CapabilityCatalog["capabilities"];
const asIndex = (v: unknown) =>
  v as {
    version: number;
    capabilities: { id: string }[];
    tools: { name: string; [k: string]: any }[];
    skills: { name: string }[];
  };

test("the index has version 1 and the capabilities, tools and skills", async () => {
  const server = stubServer({
    web_search: {
      title: "Web search",
      description: "Search.",
      annotations: { readOnlyHint: true, openWorldHint: true },
    },
  });
  const skill = {
    name: "s",
    description: "d",
    capabilities: ["web"],
    instructions: "i",
  };
  const index = asIndex(
    await buildIndex(catalogOf(asCaps(remoteCap), [skill]), server),
  );
  assert.equal(index.version, 1);
  assert.deepEqual(
    index.capabilities.map((c) => c.id),
    ["web"],
  );
  assert.deepEqual(index.skills, [skill]);
  const [tool] = index.tools;
  assert.equal(tool?.name, "web_search");
  assert.equal(tool?.title, "Web search");
  assert.equal(tool?.description, "Search.");
  assert.equal((tool?.inputSchema as any).properties.q.type, "string");
  assert.deepEqual(tool?.annotations, {
    readOnlyHint: true,
    openWorldHint: true,
  });
});

test("openWorldHint false is preserved, not dropped", async () => {
  const server = stubServer({
    music_find: { annotations: { readOnlyHint: true, openWorldHint: false } },
  });
  const index = asIndex(
    await buildIndex(
      catalogOf(
        asCaps(cap("music", [{ name: "music_find", where: "remote" }])),
      ),
      server,
    ),
  );
  assert.equal(index.tools[0]?.annotations.openWorldHint, false);
});

test("a capability whose required remote tool is not registered is omitted, with its skills", async () => {
  const server = stubServer({ web_search: {} });
  const weatherCap = cap("weather", [{ name: "get_weather", where: "hybrid" }]);
  const skill = {
    name: "w",
    description: "d",
    capabilities: ["weather"],
    instructions: "i",
  };
  const index = asIndex(
    await buildIndex(catalogOf(asCaps(remoteCap, weatherCap), [skill]), server),
  );
  assert.deepEqual(
    index.capabilities.map((c) => c.id),
    ["web"],
  );
  assert.deepEqual(index.skills, []);
  assert.deepEqual(
    index.tools.map((t) => t.name),
    ["web_search"],
  );
});

test("a missing tool marked required: false keeps the capability, and a missing local tool is the phone's concern", async () => {
  const server = stubServer({ web_search: {} });
  const mixed = cap("mixed", [
    { name: "web_search", where: "remote" },
    { name: "extra", where: "remote", required: false },
    { name: "phone_only", where: "local" },
  ]);
  const index = asIndex(await buildIndex(catalogOf(asCaps(mixed)), server));
  assert.deepEqual(
    index.capabilities.map((c) => c.id),
    ["mixed"],
  );
  assert.deepEqual(
    index.tools.map((t) => t.name),
    ["web_search"],
  );
});

test("tool definitions are unique and sorted, and only for tools in included capabilities", async () => {
  const server = stubServer({ b_tool: {}, a_tool: {}, unused: {} });
  const one = cap("one", [
    { name: "b_tool", where: "remote" },
    { name: "a_tool", where: "remote" },
  ]);
  const two = cap("two", [{ name: "b_tool", where: "hybrid" }]);
  const index = asIndex(await buildIndex(catalogOf(asCaps(one, two)), server));
  assert.deepEqual(
    index.tools.map((t) => t.name),
    ["a_tool", "b_tool"],
  );
});

test("a skill is kept when any of its capabilities is included", async () => {
  const server = stubServer({ web_search: {} });
  const skill = {
    name: "s",
    description: "d",
    capabilities: ["gone", "web"],
    instructions: "i",
  };
  const index = asIndex(
    await buildIndex(
      catalogOf(
        asCaps(remoteCap, cap("gone", [{ name: "missing", where: "remote" }])),
        [skill],
      ),
      server,
    ),
  );
  assert.deepEqual(
    index.skills.map((s) => s.name),
    ["s"],
  );
});
