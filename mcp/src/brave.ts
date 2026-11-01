import type { Config } from "./config.js";

/** One page of a search result, trimmed for the model. */
export interface SearchResult {
  title: string;
  url: string;
  /** When the page was published or last updated (YYYY-MM-DD), if Brave knows. */
  date?: string;
  text: string;
}

export class SearchError extends Error {}

interface LlmContextResponse {
  grounding?: {
    generic?: { url: string; title: string; snippets?: string[] }[];
  };
  sources?: Record<string, { age?: string[] }>;
}

/**
 * Brave's LLM Context endpoint: ranked pages with text extracted for models, rather than the short
 * snippets of web search, which often miss the figure asked for (docs/phase3-notes.md). Brave returns
 * dozens of pages, many with only a fragment; the top few in rank order, each with an equal share of the
 * text budget, carry the answer.
 */
export async function search(
  config: Config,
  query: string,
): Promise<SearchResult[]> {
  const url = new URL("https://api.search.brave.com/res/v1/llm/context");
  url.searchParams.set("q", query);
  url.searchParams.set("country", config.searchCountry);
  let response: Response;
  try {
    response = await fetch(url, {
      headers: {
        accept: "application/json",
        "x-subscription-token": config.braveApiKey,
      },
      signal: AbortSignal.timeout(10_000),
    });
  } catch (e) {
    throw new SearchError(`Search service unreachable: ${String(e)}`);
  }
  if (!response.ok)
    throw new SearchError(`Search service returned ${response.status}`);
  const json = (await response.json()) as LlmContextResponse;

  const results: SearchResult[] = [];
  const perPage = Math.floor(
    config.searchMaxChars / Math.max(1, config.searchMaxPages),
  );
  for (const page of json.grounding?.generic ?? []) {
    if (results.length >= config.searchMaxPages) break;
    const text = (page.snippets ?? []).join("\n…\n").slice(0, perPage);
    if (!text) continue;
    // Brave's `age` lists the same date in several forms; the second is ISO.
    const date = json.sources?.[page.url]?.age?.[1];
    results.push({
      title: page.title,
      url: page.url,
      ...(date ? { date } : {}),
      text,
    });
  }
  return results;
}
