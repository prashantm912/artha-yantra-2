import { ChangeDetectionStrategy, Component, inject, signal } from '@angular/core';
import { HttpClient } from '@angular/common/http';
import { ButtonModule } from 'primeng/button';
import { TagModule } from 'primeng/tag';
import { SessionStore } from '../../../core/session.store';

interface KiteStatus {
  connected: boolean;
  profile?: string;
  state?: string;
  kiteUserId?: string;
  tokenValidUntil?: string;
  tickerState?: string;
  circuitState?: string;
}

/**
 * KiteStatus widget (E-2): token health + ticker/circuit state and the OAuth popup trigger. In
 * mock mode it shows the credential-free banner — there is no Kite session to connect.
 */
@Component({
  selector: 'ay-kite-status-widget',
  changeDetection: ChangeDetectionStrategy.OnPush,
  imports: [ButtonModule, TagModule],
  styles: `
    dl {
      margin: 0 0 0.6rem;
      display: grid;
      grid-template-columns: auto 1fr;
      gap: 0.2rem 0.8rem;
      font-size: 0.85rem;
    }
    dt {
      color: var(--ay-text-muted);
    }
    dd {
      margin: 0;
      font-variant-numeric: tabular-nums;
    }
    .banner {
      font-size: 0.8rem;
      color: var(--ay-warn);
      margin-bottom: 0.6rem;
    }
  `,
  template: `
    @if (session.mockMode()) {
      <p class="banner">MOCK MODE — credential-free synthetic feed, no Kite session required.</p>
    }
    @if (status(); as k) {
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
        <dt>Ticker</dt>
        <dd>{{ k.tickerState ?? '—' }}</dd>
        <dt>Token valid until</dt>
        <dd>{{ k.tokenValidUntil ? k.tokenValidUntil.slice(0, 16).replace('T', ' ') : '—' }}</dd>
      </dl>
    }
    <p-button
      size="small"
      label="Connect Kite"
      icon="pi pi-external-link"
      [outlined]="true"
      (onClick)="connect()"
    />
  `,
})
export class KiteStatusWidget {
  protected readonly session = inject(SessionStore);
  private readonly http = inject(HttpClient);
  protected readonly status = signal<KiteStatus | null>(null);

  constructor() {
    this.http.get<KiteStatus>('/api/v1/auth/kite/status').subscribe({
      next: (s) => this.status.set(s),
      error: () => undefined,
    });
  }

  /** OAuth popup: fetch the login URL and open it (postMessage round-trip handled on /settings). */
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
}
