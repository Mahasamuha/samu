# Capabilities

The manifest that groups tools into capabilities (phase 3.5). The model sees capabilities, not raw tools: eager ones are bound at session start, lazy ones are listed by id and description and bound on demand. Skills (`skills/`) attach to capabilities.

```
capabilities/capabilities.json
```

```json
{
  "capabilities": [
    {
      "id": "navigation",
      "title": "Navigation",
      "description": "One line for the model: what it does and when to use it.",
      "load": "eager",
      "tools": [
        { "name": "navigate", "where": "local" },
        { "name": "find_contact", "where": "local", "required": false }
      ]
    }
  ]
}
```

Fields:
- `id`: unique, lowercase; keep entries sorted by it. Skills refer to it.
- `title`: short display name.
- `description`: one line written for the model. For a lazy capability this is all the model sees until it binds it, so say when to use it and when not to.
- `load`: `eager` (bound at session start) or `lazy` (listed, bound when the model asks for it).
- `tools`: the tools it groups. Each has a `name`, a `where`, and optionally `required`.
  - `where`: `local` (runs on the phone), `remote` (served by the MCP server), or `hybrid` (remote preferred, with the local tool of the same name as the fallback).
  - `required` defaults to true and is written only when false. A capability is available only when its required tools are. An optional tool that is missing is left out and the capability is still listed, so skills must not assume it.

## Tiers

There is no tier field; it is derived from `where`:
- All tools `local`: tier local. Lives in the APK only; the server never sees it.
- All tools `remote`: tier server. Served by the MCP server, so it can't include a local tool.
- Anything else: tier hybrid. The APK owns the capability's existence and its local tools; the server supplies the remote tools and their definitions, and may override `description` and `load`.

## Rules

- A tool can belong to several capabilities (`find_contact` is in calling, texting and navigation). Binding skips tools that are already bound.
- Every local tool must be in at least one local or hybrid capability.
- Private remote tools (ones the app calls itself, not the model) are in no capability.
- Tool names must match the tool classes in the app and the tools registered in `mcp/`.

## Consumers

Two consumers read the manifest:
- Gradle bundles the local and hybrid entries into the APK, and fails the build on a local tool that is in no local or hybrid capability.
- The MCP server serves the hybrid and server entries, filtered to the tools it registered, through the `capability://index` resource.
