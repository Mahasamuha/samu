# Running tests

CI (`.github/workflows/checks.yml`) runs all of these on every push and pull request; `images.yml` runs it first.

**gateway/ and mcp/** — `node:test` with `node:assert/strict`, no extra dependency. Tests live in `<unit>/test/*.test.ts`
and import from `../src/*.js`. `tsc -p test` (config in `test/tsconfig.json`) compiles `src` and `test` into the gitignored `build-test/`
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
formatting and oxlint (type-aware, through oxlint-tsgolint) for lint, with a rule set that mirrors ESLint's `recommended`
plus typescript-eslint's `recommendedTypeChecked`. It has no style rules, so it never fights Prettier.

**app/** — ktlint through the `org.jlleitschuh.gradle.ktlint` plugin (chosen over kotlinter for its wider use and its
`ktlintRuleset` configuration, which loads the Compose rules). Versions are pinned in `gradle/libs.versions.toml`, including
the ktlint engine, which should match the release the Compose ruleset is built against. Rules are configured in
`app/.editorconfig`, scoped to `app/` because the TypeScript units use Prettier instead.

```
cd app
./gradlew ktlintCheck     # main, test and Gradle scripts
./gradlew ktlintFormat    # auto-fix; run it twice, some rules only settle on the second pass
```

**gateway/ and mcp/** — each unit has its own copy of `.oxlintrc.json`, `.prettierrc.json` and `.prettierignore`.

```
cd gateway   # or mcp
pnpm format:check   # prettier --check
pnpm format         # prettier --write
pnpm lint           # oxlint --type-aware src test
```

Prettier runs on its defaults (including `printWidth` 80); the repo has no reason to differ.

oxlint plus oxlint-tsgolint (type-aware linting on typescript-go) was chosen on 2026-11-01 because typescript-eslint
doesn't support TypeScript 7 (typescript-eslint/typescript-eslint#10940). The alternative, aliasing `typescript` to
`@typescript/typescript6` for ESLint, would change which compiler the production build resolves just to satisfy a lint
tool. Notes:

- `.oxlintrc.json` is JSONC, so comments are allowed. It enables the equivalents of ESLint `recommended` and
  typescript-eslint `recommendedTypeChecked` explicitly, with the `correctness` category and extra plugins off.
- One deviation: the node:test functions (`test`, `describe`, `it`) are allowed to go unawaited under
  `typescript/no-floating-promises` (`allowForKnownSafeCalls`), because the runner collects them.
- `oxlint` and `oxlint-tsgolint` are pinned exactly and must be bumped together (oxlint's peer dependency sets the
  minimum tsgolint).
- tsgolint only discovers files named `tsconfig.json`, which is why the test config is `test/tsconfig.json` rather than
  `tsconfig.test.json`: `src` resolves to the root config and `test` to its own.
- Known gap: oxlint's `no-unused-vars` ignores `_`-prefixed variables, which typescript-eslint's recommended config
  would flag.

**Deviations.** Any departure from the style is either configured in `.editorconfig` or the oxlint config with a comment
saying why, or suppressed at the spot with a reason: `@Suppress("rule")` in Kotlin, or
`// oxlint-disable-next-line rule -- reason` in TypeScript. Don't add one just to make a check pass.

# Dependency verification

`app/gradle/verification-metadata.xml` pins a SHA-256 checksum for every artifact Gradle resolves (libraries, plugins
and their POM/module files). Gradle fails the build if a downloaded file doesn't match, so a tampered or swapped artifact
(including one served by a compromised repository) is caught. Checksums only; signature verification is off. JitPack is
declared as exclusive content for the one group that needs it, so it can't serve anything else.

When a dependency or plugin changes, regenerate from `app/` with an empty Gradle cache and no configuration cache:

```
GRADLE_USER_HOME="$(mktemp -d)" ./gradlew --no-daemon --no-configuration-cache \
  --write-verification-metadata sha256 ktlintCheck testDebugUnitTest assembleDebug assembleRelease lint
```

Both matter. Some files (parent POMs and BOMs on the plugin classpath) are only resolved when they aren't cached yet,
and a reused configuration cache skips plugin-classpath resolution entirely; either way they go unrecorded and CI,
which starts empty, fails verification on them. Check the result the same way: run
`GRADLE_USER_HOME="$(mktemp -d)" ./gradlew --no-daemon ktlintCheck testDebugUnitTest assembleDebug` and expect it to
pass. `lint` is included so its tooling dependencies are covered; it may report lint errors, which is separate from the
metadata being written. Then review the diff of the metadata file: new entries should match the dependency you
intended to add or bump, and nothing else (no `<trust>` blanket entries, no unrelated groups). Don't regenerate just
to make a verification failure go away; a mismatch on an unchanged version is the case the file exists to catch.
