# Skills

Instruction bundles for the assistant, in the Agent Skills `SKILL.md` format (plan §4b). One folder per skill:

```
skills/<name>/SKILL.md
```

`SKILL.md` is YAML frontmatter followed by plain instructions for the model:

```yaml
---
name: weather                 # unique; a server draft with the same name overrides the bundled skill
description: One line on what it's for and when to use it.
delivery: app                 # app = bundled into the APK at build time; server = draft, served by the server (§4b step 4)
tools: [get_weather]          # tools it relies on; omit for instruction-only skills
capabilities: [weather]       # capabilities it attaches to (capabilities/capabilities.json); omit for instruction-only skills
---
Instructions...
```

Frontmatter is a small YAML subset: `key: value` lines, and `tools` and `capabilities` as `[a, b]` lists.

Rules:
- `capabilities` names the capabilities the skill attaches to (`capabilities/README.md`). The skill is active whenever any of them is bound. Instruction-only skills omit it and are always on.
- `tools` is still what the app reads to gate a skill (included only when every tool it lists is available in that session). It goes away once session binding lands (phase 3.5 WP-4), leaving `capabilities` as the only link.
- The phone doesn't filter skill text by tool, so word optional tools conditionally ("if find_contact is available, ...") and say what to do without them.
- Skills can't change confirmation behavior: that's enforced per tool in the app.
- Write for the ear and keep to the domain. The base prompt (`VoicePrompt.kt`) already covers brevity, plain text, no narration, asking short which-one questions, and how to use tools and actions: say nothing before acting, leave confirmation to the phone, don't claim success early, handle `not_confirmed`, look things up fresh, never invent details. A skill only adds what's specific to its domain.
