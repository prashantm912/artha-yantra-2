/**
 * The oipulse 4-state OI interpretation (primitive #1): price direction × OI direction. Mirrors the
 * backend `OiInterpretation` enum (serialized as its name). The label is the non-colour cue — each
 * state reads distinctly, so the badge never relies on the p-tag severity colour alone.
 */
export type OiInterpretation =
  | 'LONG_BUILDUP'
  | 'SHORT_BUILDUP'
  | 'SHORT_COVERING'
  | 'LONG_UNWINDING';

export interface OiIntMeta {
  /** Human label — the textual (non-colour) cue. */
  readonly label: string;
  /** PrimeNG p-tag severity. */
  readonly severity: 'success' | 'danger' | 'info' | 'warn';
  /** Price/OI direction breakdown (tooltip). */
  readonly arrow: string;
}

const META: Record<OiInterpretation, OiIntMeta> = {
  LONG_BUILDUP: { label: 'Long Buildup', severity: 'success', arrow: 'price up · OI up' },
  SHORT_BUILDUP: { label: 'Short Buildup', severity: 'danger', arrow: 'price down · OI up' },
  SHORT_COVERING: { label: 'Short Covering', severity: 'info', arrow: 'price up · OI down' },
  LONG_UNWINDING: { label: 'Long Unwinding', severity: 'warn', arrow: 'price down · OI down' },
};

export function oiIntMeta(value: OiInterpretation): OiIntMeta {
  return META[value];
}
