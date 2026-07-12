import { useCallback, useState } from 'react';

// Saved filter views for the insight feed (INT design §8.2). Interim localStorage store — the
// P1-Phase-4 `user_prefs` server table isn't shipped yet, so a named filter set persists per-browser
// here and swaps to user_prefs when that lands (§12 I3). Pure + JSON-safe; a corrupt/absent store reads
// as an empty list rather than throwing.

const KEY = 'ay.insights.saved-views';

/** A named insight-feed filter set (the fields the feed control-bar carries). */
export interface SavedView {
  name: string;
  type: string;
  severity: string;
  status: string;
  scope: string;
  day: string | null;
  includeSuppressed: boolean;
}

function read(): SavedView[] {
  try {
    const raw = localStorage.getItem(KEY);
    if (!raw) return [];
    const parsed = JSON.parse(raw) as unknown;
    return Array.isArray(parsed) ? (parsed as SavedView[]) : [];
  } catch {
    return [];
  }
}

function write(views: SavedView[]): void {
  try {
    localStorage.setItem(KEY, JSON.stringify(views));
  } catch {
    /* private-mode / quota — the feed still works, just without persistence */
  }
}

/** Named saved views with save (upsert by name) + remove, persisted to localStorage. */
export function useSavedViews() {
  const [views, setViews] = useState<SavedView[]>(read);

  const save = useCallback((view: SavedView) => {
    setViews((prev) => {
      const next = [...prev.filter((v) => v.name !== view.name), view];
      write(next);
      return next;
    });
  }, []);

  const remove = useCallback((name: string) => {
    setViews((prev) => {
      const next = prev.filter((v) => v.name !== name);
      write(next);
      return next;
    });
  }, []);

  return { views, save, remove };
}
