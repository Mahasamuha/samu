// Preloaded into the gateway child process by tests (`node --import`). Config requires an https issuer and JWKS URL,
// so this redirects fetches for the placeholder https origin to a local http server. Test-only; no production change.
const from = process.env.TEST_FETCH_FROM;
const to = process.env.TEST_FETCH_TO;
const realFetch = globalThis.fetch;

if (from && to) {
  globalThis.fetch = (input: string | URL | Request, init?: RequestInit) => {
    const target =
      typeof input === "string"
        ? input
        : input instanceof URL
          ? input.href
          : input.url;
    return realFetch(
      target.startsWith(from) ? to + target.slice(from.length) : input,
      init,
    );
  };
}
