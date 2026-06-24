// Chain-grid row density (revamp §4.2/Q4): Compact is the scalper default. Persisted to localStorage
// (try/catch for private mode, mirroring theme.ts). Drives the per-row cell padding in OptionsChainTable.

export type Density = 'comfortable' | 'compact';

const DENSITY_KEY = 'ay.chainDensity';
const DEFAULT_DENSITY: Density = 'compact';

export function loadDensity(): Density {
  try {
    const saved = localStorage.getItem(DENSITY_KEY);
    if (saved === 'comfortable' || saved === 'compact') return saved;
  } catch {
    /* private mode */
  }
  return DEFAULT_DENSITY;
}

export function saveDensity(d: Density): void {
  try {
    localStorage.setItem(DENSITY_KEY, d);
  } catch {
    /* private mode */
  }
}
