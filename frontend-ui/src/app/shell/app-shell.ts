import { ChangeDetectionStrategy, Component, OnDestroy, inject, signal } from '@angular/core';
import { RouterLink, RouterLinkActive, RouterOutlet } from '@angular/router';
import { ButtonModule } from 'primeng/button';
import { TagModule } from 'primeng/tag';
import { ToastModule } from 'primeng/toast';
import { TooltipModule } from 'primeng/tooltip';
import { SessionStore } from '../core/session.store';
import { WsClientService } from '../core/ws-client.service';

/**
 * AppShell (C-2.24): TopBar (IST market clock, WS status placeholder until Phase 26, theme
 * toggle, mock banner) + collapsible SideNav rail + lazy RouterOutlet + ToastHost.
 */
@Component({
  selector: 'ay-shell',
  changeDetection: ChangeDetectionStrategy.OnPush,
  imports: [
    RouterOutlet,
    RouterLink,
    RouterLinkActive,
    ButtonModule,
    TagModule,
    ToastModule,
    TooltipModule,
  ],
  styles: `
    .shell {
      display: grid;
      grid-template-rows: 3.2rem 1fr;
      grid-template-columns: auto 1fr;
      height: 100vh;
    }
    .topbar {
      grid-column: 1 / 3;
      display: flex;
      align-items: center;
      gap: 0.9rem;
      padding: 0 1rem;
      border-bottom: 1px solid var(--ay-border);
      background: var(--ay-surface-1);
    }
    .brand {
      font-weight: 700;
      color: var(--ay-text);
      letter-spacing: 0.4px;
    }
    .clock {
      margin-left: auto;
      font-variant-numeric: tabular-nums;
      color: var(--ay-text-muted);
      font-size: 0.9rem;
    }
    nav {
      display: flex;
      flex-direction: column;
      gap: 0.3rem;
      padding: 0.8rem 0.5rem;
      border-right: 1px solid var(--ay-border);
      background: var(--ay-surface-1);
      width: 10rem;
    }
    nav.collapsed {
      width: 3.4rem;
    }
    nav a {
      color: var(--ay-text-muted);
      text-decoration: none;
      padding: 0.45rem 0.7rem;
      border-radius: 8px;
      font-size: 0.92rem;
      white-space: nowrap;
      overflow: hidden;
    }
    nav a.active,
    nav a:hover {
      color: var(--ay-text);
      background: var(--ay-surface-2);
    }
    main {
      overflow: auto;
      padding: 1rem;
      background: var(--ay-surface-0);
    }
  `,
  template: `
    <div class="shell">
      <header class="topbar">
        <p-button
          [text]="true"
          icon="pi pi-bars"
          ariaLabel="Toggle navigation"
          (onClick)="collapsed.set(!collapsed())"
        />
        <span class="brand">ArthaYantra</span>
        @if (session.mockMode()) {
          <p-tag severity="warn" value="MOCK MODE" pTooltip="Credential-free synthetic feed" />
        }
        <span class="clock" aria-label="IST market clock">{{ istClock() }} IST</span>
        <p-tag
          [severity]="ws.state() === 'connected' ? 'success' : 'warn'"
          [value]="'WS ' + ws.state()"
          pTooltip="Gateway STOMP socket"
        />
        <p-button
          [text]="true"
          [icon]="session.theme() === 'dark' ? 'pi pi-sun' : 'pi pi-moon'"
          ariaLabel="Toggle theme"
          (onClick)="session.toggleTheme()"
        />
        <p-button
          [text]="true"
          icon="pi pi-sign-out"
          ariaLabel="Log out"
          (onClick)="session.logout()"
        />
      </header>
      <nav [class.collapsed]="collapsed()">
        <a routerLink="/dashboard" routerLinkActive="active">Dashboard</a>
        <a routerLink="/signals" routerLinkActive="active">Signals</a>
        <a routerLink="/paper" routerLinkActive="active">Paper</a>
        <a routerLink="/journal" routerLinkActive="active">Journal</a>
        <a routerLink="/charts" routerLinkActive="active">Charts</a>
        <a routerLink="/options" routerLinkActive="active">Options</a>
        <a routerLink="/futures" routerLinkActive="active">Futures</a>
        <a routerLink="/oi/options" routerLinkActive="active">Options OI</a>
        <a routerLink="/oi/futures" routerLinkActive="active">Futures OI</a>
        <a routerLink="/strategies" routerLinkActive="active">Strategies</a>
        <a routerLink="/backtests/run" routerLinkActive="active">Backtests</a>
        <a routerLink="/backtests/jobs" routerLinkActive="active">Jobs</a>
        <a routerLink="/watchlists" routerLinkActive="active">Watchlists</a>
        <a routerLink="/settings" routerLinkActive="active">Settings</a>
      </nav>
      <main>
        <router-outlet />
      </main>
    </div>
    <p-toast position="bottom-right" />
  `,
})
export class AppShell implements OnDestroy {
  protected readonly session = inject(SessionStore);
  protected readonly ws = inject(WsClientService);
  protected readonly collapsed = signal(false);
  protected readonly istClock = signal(istNow());

  // signal writes drive zoneless rendering — no Zone patching, no markForCheck (C-2.21)
  private readonly clockTimer = setInterval(() => this.istClock.set(istNow()), 1000);

  constructor() {
    this.session.refreshSystemStatus();
  }

  ngOnDestroy(): void {
    clearInterval(this.clockTimer);
  }
}

function istNow(): string {
  return new Intl.DateTimeFormat('en-IN', {
    timeZone: 'Asia/Kolkata',
    hour: '2-digit',
    minute: '2-digit',
    second: '2-digit',
    hour12: false,
  }).format(new Date());
}
