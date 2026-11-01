# Running tests

CI (`.github/workflows/checks.yml`) runs all of these on every push and pull request; `images.yml` runs it first.

**gateway/ and mcp/** — `node:test` with `node:assert/strict`, no extra dependency. Tests live in `<unit>/test/*.test.ts`
and import from `../src/*.js`. `tsc -p tsconfig.test.json` compiles `src` and `test` into the gitignored `build-test/`
(Node's type stripping can't handle this code), then `node --test` runs the compiled files.

```
cd gateway   # or mcp
pnpm test        # compile and run
pnpm typecheck   # production and test code
```

**app/** — JVM unit tests (JUnit 4) in `app/src/test/java/...`, mirroring the main package. The real `org.json` is on the
test classpath; `android.util.Log` and similar return defaults. No Robolectric or mocking library.

```
cd app
./gradlew testDebugUnitTest
```

The app build needs `app/config.local.properties`; copy `config.example.properties` there (placeholders are enough).

# Style and lint

CI runs these before the tests, as separate checks (they are not part of `assemble`). Formatting is mechanical; fix it with the
formatter, not by hand.

**Guides.** Kotlin follows the [Kotlin coding conventions](https://kotlinlang.org/docs/coding-conventions.html) through
ktlint's `ktlint_official` style, plus the [Compose API guidelines](https://github.com/androidx/androidx/blob/androidx-main/compose/docs/compose-api-guidelines.md)
through the [compose-rules](https://mrmans0n.github.io/compose-rules/) ktlint ruleset. TypeScript uses Prettier for
formatting and typescript-eslint's `recommended` and `recommendedTypeChecked` presets for lint (`eslint-config-prettier`
last, so ESLint never fights Prettier).

**app/** — ktlint through the `org.jlleitschuh.gradle.ktlint` plugin (chosen over kotlinter for its wider use and its
`ktlintRuleset` configuration, which loads the Compose rules). Versions are pinned in `gradle/libs.versions.toml`, including
the ktlint engine, which should match the release the Compose ruleset is built against. Rules are configured in
`app/.editorconfig`, scoped to `app/` because the TypeScript units use Prettier instead.

```
cd app
./gradlew ktlintCheck     # main, test and Gradle scripts
./gradlew ktlintFormat    # auto-fix; run it twice, some rules only settle on the second pass
```

**gateway/ and mcp/** — each unit has its own copy of `eslint.config.js`, `.prettierrc.json` and `.prettierignore`.

```
cd gateway   # or mcp
pnpm format:check   # prettier --check
pnpm format         # prettier --write
pnpm lint           # eslint src test
```

Prettier runs on its defaults (including `printWidth` 80); the repo has no reason to differ. ESLint has one deviation: the
node:test functions (`test`, `describe`, `it`) are allowed to go unawaited under `no-floating-promises`, because the runner
collects them.

typescript-eslint 8.x supports TypeScript below 6.1 only and refuses to load under TypeScript 7 (tracked upstream in
typescript-eslint/typescript-eslint#10940). Until that is resolved, `pnpm lint` fails at startup, and CI doesn't run it
(decided 2026-11-01). The documented workaround, aliasing `typescript` to `@typescript/typescript6` and installing
TypeScript 7 as `@typescript/native` for `tsc`, was rejected: it changes which compiler the production build resolves just
to satisfy a lint tool. When typescript-eslint supports TypeScript 7, bump it, fix the findings (about 5 in gateway and
60 in mcp, mostly loose `any` handling of JSON in mcp tests), and restore the CI step.

**Deviations.** Any departure from the style is either configured in `.editorconfig` or the ESLint config with a comment
saying why, or suppressed at the spot with a reason: `@Suppress("rule")` in Kotlin, or
`// eslint-disable-next-line rule -- reason` in TypeScript. Don't add one just to make a check pass.
