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
