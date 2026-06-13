/** Guard-7 train→OOS degradation band (E-12.3): a DIFFERENCE (train − OOS), display-only. */
export type DegradationBand = 'consistent' | 'degrades' | 'distrust' | 'na';

export interface DegradationVerdict {
  band: DegradationBand;
  severity: 'success' | 'warn' | 'danger' | 'secondary';
  label: string;
}

/**
 * Map a `sharpe_degradation` value to its traffic-light band. Suppressed to n/a when the value is
 * absent or the train Sharpe is weak (< 0.5) — divergence is meaningless without a real train signal.
 */
export function degradationBand(
  value: string | number | null | undefined,
  trainSharpe?: string | number | null,
): DegradationVerdict {
  if (value == null || value === '') {
    return { band: 'na', severity: 'secondary', label: 'n/a' };
  }
  if (trainSharpe != null && trainSharpe !== '' && Number(trainSharpe) < 0.5) {
    return { band: 'na', severity: 'secondary', label: 'n/a — weak train signal' };
  }
  const d = Number(value);
  if (Number.isNaN(d)) {
    return { band: 'na', severity: 'secondary', label: 'n/a' };
  }
  if (d < 0.3) {
    return { band: 'consistent', severity: 'success', label: 'consistent' };
  }
  if (d <= 1.0) {
    return { band: 'degrades', severity: 'warn', label: 'degrades' };
  }
  return { band: 'distrust', severity: 'danger', label: 'distrust' };
}
