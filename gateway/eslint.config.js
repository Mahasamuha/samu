import eslint from "@eslint/js";
import prettier from "eslint-config-prettier";
import tseslint from "typescript-eslint";

export default tseslint.config(
  { ignores: ["dist", "build-test", "node_modules", "eslint.config.js"] },
  eslint.configs.recommended,
  tseslint.configs.recommendedTypeChecked,
  {
    languageOptions: {
      parserOptions: {
        // tsconfig.test.json covers both src and test; tsconfig.json is src only.
        project: ["./tsconfig.test.json"],
        tsconfigRootDir: import.meta.dirname,
      },
    },
    rules: {
      // node:test's test(), describe() and it() return promises that the runner collects itself; awaiting
      // them is not needed, and the rule would flag every test.
      "@typescript-eslint/no-floating-promises": [
        "error",
        {
          allowForKnownSafeCalls: [
            {
              from: "package",
              package: "node:test",
              name: ["test", "describe", "it"],
            },
          ],
        },
      ],
    },
  },
  // Must stay last: turns off the rules that conflict with Prettier.
  prettier,
);
