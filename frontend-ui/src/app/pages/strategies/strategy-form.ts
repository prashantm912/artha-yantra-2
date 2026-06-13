import { parse, stringify } from 'yaml';

/** The parsed strategy doc — loosely typed; only the CD-11 form fields are read/written in v1. */
export type StrategyDoc = {
  name?: string;
  tags?: string[];
  indicators?: Record<string, unknown>[];
} & Record<string, unknown>;

/** One indicator row the form pane renders (weight + optional are the only editable fields, CD-11). */
export interface IndicatorForm {
  i: number;
  alias: string;
  name: string;
  weight: number;
  optional: boolean;
}

/** Parse YAML to a doc object; null when it can't be parsed (the form pane then disables). */
export function parseDoc(yaml: string): StrategyDoc | null {
  try {
    const doc = parse(yaml) as StrategyDoc;
    return doc && typeof doc === 'object' ? doc : null;
  } catch {
    return null;
  }
}

/** Read the indicator rows for the form pane. */
export function formIndicators(doc: StrategyDoc | null): IndicatorForm[] {
  const inds = doc?.indicators;
  if (!Array.isArray(inds)) {
    return [];
  }
  return inds.map((ind, i) => ({
    i,
    alias: String(ind['alias'] ?? `#${i}`),
    name: String(ind['name'] ?? ''),
    weight:
      typeof ind['weight'] === 'number' ? (ind['weight'] as number) : Number(ind['weight'] ?? 0),
    optional: ind['optional'] === true,
  }));
}

/** Apply a mutation to the parsed doc and re-stringify; returns the original text on a parse error. */
export function withField(yaml: string, mutate: (doc: StrategyDoc) => void): string {
  const doc = parseDoc(yaml);
  if (!doc) {
    return yaml;
  }
  mutate(doc);
  return stringify(doc);
}
