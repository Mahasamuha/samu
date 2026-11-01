import { createServer } from "node:http";
import { loadCatalog } from "./capabilities.js";
import { loadConfig } from "./config.js";
import { createHandlers, log, send } from "./server.js";

const config = loadConfig();
const catalog = loadCatalog();
const { handle } = createHandlers(config, catalog);

// Routes: see handle() in server.ts.
const httpServer = createServer((req, res) => {
  handle(req, res).catch((e: unknown) => {
    log("unhandled_error", { error: e instanceof Error ? e.stack : String(e) });
    if (!res.headersSent) send(res, 500, { error: "Internal error" });
    else res.destroy();
  });
});
httpServer.listen(config.port, () => log("listening", { version: config.version, port: config.port }));

for (const signal of ["SIGTERM", "SIGINT"] as const) {
  process.on(signal, () => {
    log("shutdown", { signal });
    httpServer.close(() => process.exit(0));
    setTimeout(() => process.exit(0), 10_000).unref();
  });
}
