// Optional-column metadata for the faithful Options Chain Column-Setting popover (§20.7.3). Plain
// data (no JSX) so it is importable by the page without tripping react-refresh; the matching render
// columns live module-locally in OptionsChainTable.tsx, keyed by these same keys.

export interface OptionalColumnMeta {
  key: string;
  label: string;
}

// Faithful to the live oipulse Column-Setting set (the renderable subset of its optional columns —
// Delta / Volume / Intrinsic). oipulse's IV-Chng / O=H / O=L / Premium / Straddle / Chart are deferred
// (they need an IV-delta field, a strike-session-stats join, or a chart widget we do not have yet).
export const OPTIONAL_COLUMN_META: OptionalColumnMeta[] = [
  { key: 'delta', label: 'Delta' },
  { key: 'volume', label: 'Volume' },
  { key: 'intrinsic', label: 'Intrinsic' },
];

export const OPTIONAL_COLUMN_KEYS = OPTIONAL_COLUMN_META.map((c) => c.key);
