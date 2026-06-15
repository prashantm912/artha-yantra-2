import { ChangeDetectionStrategy, Component, DestroyRef, inject, signal } from '@angular/core';
import { HttpClient } from '@angular/common/http';
import { ButtonModule } from 'primeng/button';
import { TagModule } from 'primeng/tag';
import { MessageService } from 'primeng/api';
import { SessionStore } from '../../core/session.store';

interface KiteStatus {
  connected: boolean;
  state?: string;
  kiteUserId?: string;
  tokenValidUntil?: string;
  tickerState?: string;
}
interface SyncStatus {
  jobId?: string;
  state?: string;
  lastRun?: string;
  durationMs?: number;
  error?: string;
}

/**
 * /settings (Phase 35, E-8): Kite OAuth (popup + postMessage round-trip), token health, theme,
 * data-sync triggers, mock-mode banner — plus a Global-risk note pointing at the Paper page, where
 * the kill switch, `strategy.risk_settings` rows and the trip audit shipped (Stage F Phase 43A — FP-42).
 */
@Component({
  selector: 'ay-settings-page',
  changeDetection: ChangeDetectionStrategy.OnPush,
  imports: [ButtonModule, TagModule],
  styles: `
    .page {
      max-width: 46rem;
      display: flex;
      flex-direction: column;
      gap: 1rem;
    }
    section {
      border: 1px solid var(--ay-border);
      border-radius: 10px;
      background: var(--ay-surface-1);
      padding: 1rem 1.1rem;
    }
    h2 {
      font-size: 0.95rem;
      margin: 0 0 0.7rem;
    }
    dl {
      display: grid;
      grid-template-columns: auto 1fr;
      gap: 0.25rem 1rem;
      margin: 0 0 0.8rem;
      font-size: 0.9rem;
    }
    dt {
      color: var(--ay-text-muted);
    }
    dd {
      margin: 0;
    }
    .actions {
      display: flex;
      gap: 0.6rem;
      align-items: center;
      flex-wrap: wrap;
    }
    .banner {
      color: var(--ay-warn);
      font-size: 0.85rem;
      margin: 0 0 0.6rem;
    }
    .reserved {
      color: var(--ay-text-muted);
      font-size: 0.85rem;
      font-style: italic;
    }
  `,
  template: `
    <h1 class="ay-sr-only">Settings</h1>
    <div class="page">
      @if (session.mockMode()) {
        <p class="banner">MOCK MODE — credential-free synthetic feed; no Kite session required.</p>
      }

      <section>
        <h2>Kite Connect</h2>
        @if (kite(); as k) {
          <dl>
            <dt>Connected</dt>
            <dd>
              <p-tag
                [severity]="k.connected ? 'success' : 'danger'"
                [value]="k.connected ? 'YES' : 'NO'"
              />
            </dd>
            <dt>State</dt>
            <dd>{{ k.state ?? '—' }}</dd>
            <dt>User</dt>
            <dd>{{ k.kiteUserId ?? '—' }}</dd>
            <dt>Token valid until</dt>
            <dd>
              {{ k.tokenValidUntil ? k.tokenValidUntil.slice(0, 16).replace('T', ' ') : '—' }}
            </dd>
            <dt>Ticker</dt>
            <dd>{{ k.tickerState ?? '—' }}</dd>
          </dl>
        }
        <div class="actions">
          <p-button
            label="Connect Kite"
            icon="pi pi-external-link"
            [outlined]="true"
            (onClick)="connect()"
          />
          <p-button
            label="Refresh status"
            icon="pi pi-refresh"
            [text]="true"
            (onClick)="loadKite()"
          />
        </div>
      </section>

      <section>
        <h2>Appearance</h2>
        <div class="actions">
          <span>Theme: {{ session.theme() }}</span>
          <p-button
            [label]="session.theme() === 'dark' ? 'Switch to light' : 'Switch to dark'"
            [icon]="session.theme() === 'dark' ? 'pi pi-sun' : 'pi pi-moon'"
            [text]="true"
            (onClick)="session.toggleTheme()"
          />
        </div>
      </section>

      <section>
        <h2>Data sync</h2>
        @if (sync(); as s) {
          <dl>
            <dt>State</dt>
            <dd>{{ s.state ?? '—' }}</dd>
            <dt>Last run</dt>
            <dd>{{ s.lastRun ? s.lastRun.slice(0, 19).replace('T', ' ') : '—' }}</dd>
          </dl>
        }
        <div class="actions">
          <p-button
            label="Sync instruments"
            icon="pi pi-sync"
            [outlined]="true"
            (onClick)="syncInstruments()"
          />
          <p-button label="Sync status" icon="pi pi-refresh" [text]="true" (onClick)="loadSync()" />
        </div>
      </section>

      <section>
        <h2>Global Risk</h2>
        <p class="reserved">
          Kill switch, daily-loss and max-open limits are managed on the Paper trading page.
        </p>
      </section>
    </div>
  `,
})
export class SettingsPage {
  protected readonly session = inject(SessionStore);
  private readonly http = inject(HttpClient);
  private readonly toast = inject(MessageService);
  protected readonly kite = signal<KiteStatus | null>(null);
  protected readonly sync = signal<SyncStatus | null>(null);

  constructor() {
    this.loadKite();
    this.loadSync();
    // The OAuth popup posts back on completion (preserved from v1) — refetch status.
    const onMessage = (e: MessageEvent): void => {
      if (
        e.origin === window.location.origin &&
        (e.data as { type?: string })?.type === 'kite-connected'
      ) {
        this.loadKite();
      }
    };
    window.addEventListener('message', onMessage);
    inject(DestroyRef).onDestroy(() => window.removeEventListener('message', onMessage));
  }

  protected loadKite(): void {
    this.http
      .get<KiteStatus>('/api/v1/auth/kite/status')
      .subscribe({ next: (s) => this.kite.set(s), error: () => undefined });
  }

  protected loadSync(): void {
    this.http
      .get<SyncStatus>('/api/v1/instruments/sync/status')
      .subscribe({ next: (s) => this.sync.set(s), error: () => undefined });
  }

  protected connect(): void {
    this.http.get<{ url: string }>('/api/v1/auth/kite/login-url').subscribe({
      next: (r) => {
        if (r.url) {
          window.open(r.url, 'kite-oauth', 'width=480,height=720');
        }
      },
      error: () => undefined,
    });
  }

  protected syncInstruments(): void {
    // HTTP errors on the trigger (409 already-running, 5xx) toast via errorEnvelopeInterceptor.
    // The run is async — a 202 here does NOT mean success, so poll status and toast the outcome.
    this.http
      .post('/api/v1/instruments/sync', {})
      .subscribe({ next: () => this.pollSyncOutcome(0), error: () => undefined });
  }

  /** Polls sync status until the run leaves RUNNING, then toasts OK / FAILED. Caps at ~60s. */
  private pollSyncOutcome(attempt: number): void {
    this.http.get<SyncStatus>('/api/v1/instruments/sync/status').subscribe({
      next: (s) => {
        this.sync.set(s);
        if (s.state === 'RUNNING' && attempt < 30) {
          setTimeout(() => this.pollSyncOutcome(attempt + 1), 2000);
        } else if (s.state === 'FAILED') {
          this.toast.add({
            severity: 'error',
            summary: 'Instrument sync failed',
            detail: s.error ?? 'See market-data-service logs.',
            life: 8000,
          });
        } else if (s.state === 'OK') {
          this.toast.add({ severity: 'success', summary: 'Instruments synced', life: 4000 });
        }
      },
      error: () => undefined,
    });
  }
}
