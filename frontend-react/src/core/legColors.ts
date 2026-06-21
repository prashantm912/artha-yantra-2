// Per-leg colours for the Multiple OI Chart, shared by the chart (line colours) and the page (chip
// swatches) so they match. The first 3 legs reuse the bull/bear/warn theme tokens (accent is reserved for
// the price line); beyond that a golden-angle HSL gives distinct, evenly-spread hues for any N.

/** Distinct golden-angle hue for OI lines beyond the first 3. Theme-independent (fixed 50% lightness). */
export function legHsl(i: number): string {
  return `hsl(${(i * 137.5) % 360} 70% 50%)`;
}

/** The tailwind swatch class for the first 3 legs (matching the chart's bull/bear/warn tokens); null after. */
export function legSwatchClass(i: number): string | null {
  return i < 3 ? ['bg-bull', 'bg-bear', 'bg-warn'][i] : null;
}
