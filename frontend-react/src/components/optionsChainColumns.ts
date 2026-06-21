// Optional-column metadata for the faithful Options Chain Column-Setting popover (§20.7.3). Plain
// data (no JSX) so it is importable by the page without tripping react-refresh; the matching render
// columns live module-locally in OptionsChainTable.tsx, keyed by these same keys.

export interface OptionalColumnMeta {
  key: string;
  label: string;
}

export const OPTIONAL_COLUMN_META: OptionalColumnMeta[] = [
  { key: 'delta', label: 'Delta' },
  { key: 'volume', label: 'Volume' },
  { key: 'bid', label: 'Bid' },
  { key: 'ask', label: 'Ask' },
];

export const OPTIONAL_COLUMN_KEYS = OPTIONAL_COLUMN_META.map((c) => c.key);
