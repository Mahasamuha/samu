const MAX_CAPTURE_BYTES = 1_000_000;

/** A streamed Responses API event. */
interface StreamEvent {
  type?: string;
  delta?: string;
  response?: { usage?: unknown };
}

/** Copies response bytes as they stream past, for the body log. Never delays the stream. */
export class ResponseCapture {
  private readonly parts: Buffer[] = [];
  private size = 0;
  private truncated = false;

  add(chunk: Uint8Array): void {
    if (this.size + chunk.byteLength > MAX_CAPTURE_BYTES) {
      this.truncated = true;
      return;
    }
    this.parts.push(Buffer.from(chunk));
    this.size += chunk.byteLength;
  }

  /** Streams are reduced to the assembled text and final usage; `raw` keeps the original bytes. */
  summarize(raw: boolean): Record<string, unknown> {
    const body = Buffer.concat(this.parts).toString("utf8");
    const events = body
      .split("\n")
      .filter((line) => line.startsWith("data:"))
      .map((line) => line.slice(5).trim())
      .filter((payload) => payload && payload !== "[DONE]");

    const summary: Record<string, unknown> = { truncated: this.truncated };
    if (events.length > 0) {
      let text = "";
      for (const payload of events) {
        const event = parseJson(payload) as StreamEvent | undefined;
        if (event?.type === "response.output_text.delta")
          text += event.delta ?? "";
        if (event?.type === "response.completed" && event.response?.usage)
          summary.usage = event.response.usage;
      }
      summary.text = text;
    } else {
      summary.body = parseJson(body) ?? body.slice(0, 10_000);
    }
    if (raw) summary.raw = body;
    return summary;
  }
}

function parseJson(text: string): unknown {
  try {
    return JSON.parse(text);
  } catch {
    return undefined;
  }
}
