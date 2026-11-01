interface Bucket {
  tokens: number;
  updatedAt: number;
}

/** In-memory token bucket per key. Resets on restart, which is acceptable. */
export class RateLimiter {
  private readonly buckets = new Map<string, Bucket>();
  private readonly refillPerMs: number;

  constructor(
    perMinute: number,
    private readonly burst: number,
  ) {
    this.refillPerMs = perMinute / 60_000;
    setInterval(() => this.prune(), 10 * 60_000).unref();
  }

  /** Returns 0 if the request may proceed, otherwise the number of seconds to wait. */
  take(key: string, now = Date.now()): number {
    const bucket = this.buckets.get(key) ?? {
      tokens: this.burst,
      updatedAt: now,
    };
    bucket.tokens = Math.min(
      this.burst,
      bucket.tokens + (now - bucket.updatedAt) * this.refillPerMs,
    );
    bucket.updatedAt = now;
    this.buckets.set(key, bucket);

    if (bucket.tokens >= 1) {
      bucket.tokens -= 1;
      return 0;
    }
    return Math.ceil((1 - bucket.tokens) / this.refillPerMs / 1000);
  }

  private prune(now = Date.now()): void {
    for (const [key, bucket] of this.buckets) {
      if (now - bucket.updatedAt > 10 * 60_000) this.buckets.delete(key);
    }
  }
}
