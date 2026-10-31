import { existsSync, readdirSync, readFileSync } from "node:fs";
import { join } from "node:path";
import type { McpServer } from "@modelcontextprotocol/sdk/server/mcp.js";
import { z } from "zod";

const toolRef = z.object({
  name: z.string().min(1),
  where: z.enum(["local", "remote", "hybrid"]),
  required: z.boolean().optional(),
});

const capability = z.object({
  id: z.string().min(1),
  title: z.string().min(1),
  description: z.string().min(1),
  load: z.enum(["eager", "lazy"]),
  tools: z.array(toolRef).min(1),
});

const manifestSchema = z.object({ capabilities: z.array(capability) });

type Capability = z.infer<typeof capability>;

export interface ServerSkill {
  name: string;
  description: string;
  capabilities: string[];
  instructions: string;
}

/** What the server knows at startup: the capabilities it may serve and its server-delivery skills. */
export interface CapabilityCatalog {
  capabilities: Capability[];
  skills: ServerSkill[];
}

/**
 * Loads the manifest and skills. Both paths come from env (CAPABILITIES_FILE, SKILLS_DIR); the defaults are the
 * copies baked into the image under /app, or the repo-root copies when running from a checkout (the compiled
 * file is mcp/dist/capabilities.js, so the repo root is two directories up from it).
 * Throws on an invalid manifest so a bad deploy fails at startup.
 */
export function loadCatalog(env: NodeJS.ProcessEnv = process.env): CapabilityCatalog {
  const root = new URL("../../", import.meta.url).pathname;
  const manifestPath = env.CAPABILITIES_FILE || defaultPath(root, "capabilities/capabilities.json");
  const skillsDir = env.SKILLS_DIR || defaultPath(root, "skills");

  const parsed = manifestSchema.safeParse(JSON.parse(readFileSync(manifestPath, "utf8")));
  if (!parsed.success) throw new Error(`Invalid capability manifest ${manifestPath}: ${z.prettifyError(parsed.error)}`);
  const ids = parsed.data.capabilities.map((c) => c.id);
  if (new Set(ids).size !== ids.length) throw new Error(`Invalid capability manifest ${manifestPath}: duplicate id`);

  // Tier is derived: all local means the APK alone owns it, so the server never sees it.
  const capabilities = parsed.data.capabilities.filter((c) => !c.tools.every((t) => t.where === "local"));
  const served = new Set(capabilities.map((c) => c.id));
  const skills = loadServerSkills(skillsDir).filter((s) => s.capabilities.some((id) => served.has(id)));
  return { capabilities, skills };
}

/** /app/<rel> inside the image (the build stage's layout), else the repo checkout. */
function defaultPath(repoRoot: string, rel: string): string {
  return existsSync(join("/app", rel)) ? join("/app", rel) : join(repoRoot, rel);
}

function loadServerSkills(dir: string): ServerSkill[] {
  if (!existsSync(dir)) return [];
  const skills: ServerSkill[] = [];
  for (const entry of readdirSync(dir, { withFileTypes: true })) {
    const file = join(dir, entry.name, "SKILL.md");
    if (!entry.isDirectory() || !existsSync(file)) continue;
    const skill = parseSkill(readFileSync(file, "utf8"), file);
    if (skill) skills.push(skill);
  }
  return skills.sort((a, b) => a.name.localeCompare(b.name));
}

/** Frontmatter is the documented YAML subset (skills/README.md). Returns undefined for skills not delivered by the server. */
function parseSkill(text: string, file: string): ServerSkill | undefined {
  const match = /^---\r?\n([\s\S]*?)\r?\n---\r?\n?([\s\S]*)$/.exec(text);
  if (!match) throw new Error(`${file}: missing frontmatter`);
  const fields = new Map<string, string>();
  for (const line of (match[1] ?? "").split(/\r?\n/)) {
    const kv = /^([A-Za-z_][\w-]*):\s*(.*?)\s*(?:\s#.*)?$/.exec(line);
    if (kv) fields.set(kv[1]!, kv[2]!);
  }
  if (fields.get("delivery") !== "server") return undefined;
  const name = fields.get("name");
  const description = fields.get("description");
  if (!name || !description) throw new Error(`${file}: name and description are required`);
  return {
    name,
    description,
    capabilities: list(fields.get("capabilities")),
    instructions: (match[2] ?? "").trim(),
  };
}

function list(value: string | undefined): string[] {
  const inner = /^\[(.*)\]$/.exec(value ?? "")?.[1] ?? "";
  return inner.split(",").map((s) => s.trim()).filter(Boolean);
}

interface ToolDefinition {
  name: string;
  [key: string]: unknown;
}

/**
 * The tools/list definitions of the tools registered on this server. This calls the SDK's own tools/list
 * handler, so schemas and annotations are exactly what a tools/list request returns, with no second
 * zod-to-JSON-Schema path to keep in sync. The handler map is not public API; the lookup fails loudly if
 * a future SDK moves it.
 */
async function registeredToolDefinitions(server: McpServer): Promise<ToolDefinition[]> {
  const handlers = (server.server as unknown as { _requestHandlers?: Map<string, (request: unknown, extra: unknown) => unknown> })._requestHandlers;
  const listTools = handlers?.get("tools/list");
  if (!listTools) throw new Error("MCP SDK changed: no tools/list handler to derive capability tool definitions from");
  const result = (await listTools({ method: "tools/list" }, {})) as { tools: ToolDefinition[] };
  return result.tools;
}

/** The capability://index document for the tools registered on `server`. Call after registering them. */
export async function buildIndex(catalog: CapabilityCatalog, server: McpServer): Promise<unknown> {
  const registered = new Map((await registeredToolDefinitions(server)).map((t) => [t.name, t]));
  // Local tools are the phone's concern. Everything else the capability needs (required ones) must be registered here.
  const capabilities = catalog.capabilities.filter((c) =>
    c.tools.every((t) => t.where === "local" || t.required === false || registered.has(t.name)),
  );
  const included = new Set(capabilities.map((c) => c.id));

  const toolNames = new Set(capabilities.flatMap((c) => c.tools.filter((t) => t.where !== "local").map((t) => t.name)));
  const tools = [...toolNames].sort().flatMap((name) => {
    const t = registered.get(name);
    if (!t) return [];
    const { name: _n, title, description, inputSchema, annotations } = t;
    return [{ name, title, description, inputSchema, annotations }];
  });

  const skills = catalog.skills.filter((s) => s.capabilities.some((id) => included.has(id)));
  return { version: 1, capabilities, tools, skills };
}
