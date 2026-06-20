// Multi-theme system (master plan §20): each theme is a complete --ay-* token set in index.css,
// swapped via data-theme on <html>, persisted to localStorage. shadcn/Tailwind alias --ay-* so the
// whole app re-themes on switch.

export const THEMES = [
  { id: 'dark', label: 'Dark' },
  { id: 'light', label: 'Light' },
  { id: 'oipulse-red', label: 'OiPulse Red' },
  { id: 'midnight-blue', label: 'Midnight Blue' },
  { id: 'high-contrast', label: 'High Contrast' },
] as const;

export type ThemeId = (typeof THEMES)[number]['id'];

const THEME_KEY = 'ay.theme';
const DEFAULT_THEME: ThemeId = 'dark';

function isThemeId(v: string | null): v is ThemeId {
  return !!v && THEMES.some((t) => t.id === v);
}

export function loadTheme(): ThemeId {
  try {
    const saved = localStorage.getItem(THEME_KEY);
    if (isThemeId(saved)) return saved;
  } catch {
    /* private mode */
  }
  // First-run: respect the OS preference (only dark/light are OS-derivable).
  if (typeof matchMedia === 'function' && matchMedia('(prefers-color-scheme: light)').matches) {
    return 'light';
  }
  return DEFAULT_THEME;
}

export function applyTheme(id: ThemeId): void {
  document.documentElement.setAttribute('data-theme', id);
  try {
    localStorage.setItem(THEME_KEY, id);
  } catch {
    /* private mode */
  }
}
