/**
 * The oipulse 4-state OI interpretation (primitive #1): price direction × OI direction. Mirrors the
 * backend `OiInterpretation` enum (serialized as its name). The label is the non-colour cue — each
 * state reads distinctly, so the badge never relies on the severity colour alone (a11y #2).
 *
 * Ported from frontend-ui/src/app/core/oi-interpretation.ts. `severity` maps to --ay-* tokens in the
 * OiBadge4 component (success→bull, danger→bear, info→accent, warn→warn).
 */
export type OiInterpretation =
  | 'LONG_BUILDUP'
  | 'SHORT_BUILDUP'
  | 'SHORT_COVERING'
  | 'LONG_UNWINDING';

export type OiSeverity = 'success' | 'danger' | 'info' | 'warn';

export interface OiIntMeta {
  /** Full human label — the accessible name (aria-label) + tooltip. */
  readonly label: string;
  /** oipulse dense-table abbreviation (the visible cue): L.B. / S.B. / S.C. / L.U. */
  readonly abbr: string;
  /** Price-direction glyph: ↑ when price rose (LB/SC), ↓ when it fell (SB/LU). */
  readonly glyph: string;
  /** Severity → token mapping key. */
  readonly severity: OiSeverity;
  /** Price/OI direction breakdown (tooltip). */
  readonly arrow: string;
}

const META: Record<OiInterpretation, OiIntMeta> = {
  LONG_BUILDUP: { label: 'Long Buildup', abbr: 'L.B.', glyph: '↑', severity: 'success', arrow: 'price up · OI up' },
  SHORT_BUILDUP: { label: 'Short Buildup', abbr: 'S.B.', glyph: '↓', severity: 'danger', arrow: 'price down · OI up' },
  SHORT_COVERING: { label: 'Short Covering', abbr: 'S.C.', glyph: '↑', severity: 'info', arrow: 'price up · OI down' },
  LONG_UNWINDING: { label: 'Long Unwinding', abbr: 'L.U.', glyph: '↓', severity: 'warn', arrow: 'price down · OI down' },
};

export function oiIntMeta(value: OiInterpretation): OiIntMeta {
  return META[value];
}
