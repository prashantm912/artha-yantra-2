// Cross-page options drill-down (audit §4.2.6 / §6.3): a chain / heatmap / spurt strike links to the
// Options Chart page pre-filtered to that strike's CE+PE contract. Only the strike rides the URL — the
// underlying / expiry / interval / mode / date all live in the shared SymbolContext store, so they
// carry over on navigation without being re-passed.

/** Deep-link to the Options Chart page for one strike (a decimal string, e.g. "57200"). */
export function optionsChartPath(strike: string): string {
  return `/options/options-chart?strike=${encodeURIComponent(strike)}`;
}
