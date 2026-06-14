import { inject } from '@angular/core';
import { HttpClient, HttpParams } from '@angular/common/http';
import { patchState, signalStore, withMethods, withState } from '@ngrx/signals';

/** A link to the object a journal entry annotates (all optional — free entries are first-class). */
export interface JournalLink {
  signalId?: number;
  paperPositionId?: number;
  backtestRunId?: string;
  backtestTradeId?: number;
}

/** One journal entry as the API delivers it. */
export interface JournalEntry extends JournalLink {
  id: number;
  note: string;
  tags: string[];
  disciplineRating: number | null;
  emotionRating: number | null;
  createdAt: string;
  updatedAt: string;
}

interface JournalState {
  items: JournalEntry[];
  tag: string | null;
  linkedTo: string | null;
  loading: boolean;
}

/** JournalStore (F-44A): the weekly-review list + create/update/delete over /api/v1/journal. */
export const JournalStore = signalStore(
  { providedIn: 'root' },
  withState<JournalState>({ items: [], tag: null, linkedTo: null, loading: false }),
  withMethods((store, http = inject(HttpClient)) => ({
    load(): void {
      patchState(store, { loading: true });
      let params = new HttpParams().set('limit', 200);
      if (store.tag()) {
        params = params.set('tag', store.tag()!);
      }
      if (store.linkedTo()) {
        params = params.set('linkedTo', store.linkedTo()!);
      }
      http.get<{ items: JournalEntry[] }>('/api/v1/journal', { params }).subscribe({
        next: (res) => patchState(store, { items: res.items ?? [], loading: false }),
        error: () => patchState(store, { loading: false }),
      });
    },

    setTag(tag: string | null): void {
      patchState(store, { tag });
      this.load();
    },

    setLinkedTo(linkedTo: string | null): void {
      patchState(store, { linkedTo });
      this.load();
    },

    create(
      body: JournalLink & {
        note: string;
        tags: string[];
        disciplineRating?: number | null;
        emotionRating?: number | null;
      },
    ): void {
      http.post<JournalEntry>('/api/v1/journal', body).subscribe({ next: () => this.load() });
    },

    update(
      id: number,
      body: {
        note: string;
        tags: string[];
        disciplineRating?: number | null;
        emotionRating?: number | null;
      },
    ): void {
      http.put<JournalEntry>(`/api/v1/journal/${id}`, body).subscribe({ next: () => this.load() });
    },

    remove(id: number): void {
      http.delete<void>(`/api/v1/journal/${id}`).subscribe({ next: () => this.load() });
    },
  })),
);
