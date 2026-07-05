// AdvanceChart view-state persistence (ADR A13: chart-state to localStorage, the v1 that replaces the
// TradingView save/load). Restores the symbol + interval + the extras toolbar (OI overlay, trade
// markers, horizontal price lines, the armed price alert) across page reloads — today every setting is
// volatile React state and is lost on refresh. try/catch guards private mode (mirrors density.ts /
// theme.ts). A URL ?symbol / ?interval still WINS over the saved value (a shared/deep link is explicit).
// Freehand drawing tools stay deferred (a large lightweight-charts-primitive lift with no consumer).

export interface ChartPrefs {
  symbol?: string;
  interval?: string;
  showOi?: boolean;
  showTrades?: boolean;
  priceLines?: number[];
  alertPrice?: number | null;
}

const KEY = 'ay.advanceChart';

export function loadChartPrefs(): ChartPrefs {
  try {
    const raw = localStorage.getItem(KEY);
    if (!raw) return {};
    const parsed = JSON.parse(raw) as unknown;
    return parsed && typeof parsed === 'object' ? (parsed as ChartPrefs) : {};
  } catch {
    return {}; // private mode / malformed
  }
}

export function saveChartPrefs(prefs: ChartPrefs): void {
  try {
    localStorage.setItem(KEY, JSON.stringify(prefs));
  } catch {
    /* private mode */
  }
}
