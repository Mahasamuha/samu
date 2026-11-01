import { appendFile, mkdir, readdir, rm, stat } from "node:fs/promises";
import path from "node:path";

export function log(event: string, fields: Record<string, unknown> = {}): void {
  console.log(
    JSON.stringify({ time: new Date().toISOString(), event, ...fields }),
  );
}

/**
 * Server-side debug toggle (SIGUSR2). While on, body logs include raw upstream events.
 * Switches itself off after a fixed duration so it can't be left on by accident.
 */
export class DebugSwitch {
  private timer: NodeJS.Timeout | undefined;

  constructor(private readonly durationMs: number) {}

  get on(): boolean {
    return this.timer !== undefined;
  }

  toggle(): void {
    if (this.on) this.disable("toggled");
    else this.enable();
  }

  private enable(): void {
    this.timer = setTimeout(() => this.disable("expired"), this.durationMs);
    this.timer.unref();
    log("debug_on", {
      offAt: new Date(Date.now() + this.durationMs).toISOString(),
    });
  }

  private disable(reason: string): void {
    clearTimeout(this.timer);
    this.timer = undefined;
    log("debug_off", { reason });
  }
}

/** Request/response bodies as hourly JSONL files, deleted once older than the retention period. */
export class BodyLog {
  constructor(
    private readonly dir: string,
    private readonly retentionMs: number,
    private readonly debug: DebugSwitch,
    private readonly debugDurationMs: number,
  ) {
    setInterval(() => {
      this.prune().catch((error: unknown) =>
        log("prune_failed", { error: String(error) }),
      );
    }, 60 * 60_000).unref();
  }

  get enabled(): boolean {
    return this.retentionMs > 0 || this.debug.on;
  }

  async init(): Promise<void> {
    await mkdir(this.dir, { recursive: true });
    await this.prune();
  }

  async write(record: Record<string, unknown>): Promise<void> {
    const hour = new Date().toISOString().slice(0, 13); // e.g. 2026-10-20T19
    await appendFile(
      path.join(this.dir, `bodies-${hour}.jsonl`),
      `${JSON.stringify(record)}\n`,
      { mode: 0o600 },
    );
  }

  /** With retention 0, files written during a debug session are kept for one debug duration. */
  private async prune(): Promise<void> {
    const keepMs =
      this.retentionMs > 0 ? this.retentionMs : this.debugDurationMs;
    const cutoff = Date.now() - keepMs;
    for (const name of await readdir(this.dir)) {
      if (!/^bodies-.*\.jsonl$/.test(name)) continue;
      const file = path.join(this.dir, name);
      // force: a file removed since readdir is already gone, not an error.
      const info = await stat(file).catch(() => undefined);
      if (info && info.mtimeMs < cutoff) await rm(file, { force: true });
    }
  }
}
