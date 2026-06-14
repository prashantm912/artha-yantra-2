import { ChangeDetectionStrategy, Component } from '@angular/core';
import { SkeletonModule } from 'primeng/skeleton';
import { WidgetShell } from './widget-shell';
import { MarketOverviewWidget } from './widgets/market-overview-widget';
import { ActiveSignalsWidget } from './widgets/active-signals-widget';
import { JobsWidget } from './widgets/jobs-widget';
import { KiteStatusWidget } from './widgets/kite-status-widget';
import { WatchlistWidget } from './widgets/watchlist-widget';
import { PaperPnlWidget } from './widgets/paper-pnl-widget';

/**
 * /dashboard (Phase 35): the at-a-glance widget grid (E-2). 12-col CSS grid, each widget in the
 * `AyWidget` shell (title + collapse, layout persisted to localStorage). Below-the-fold widgets
 * `@defer` on viewport so they don't cost in the initial render. Responsive collapse at E-5
 * breakpoints. No drag-grid in v1.
 */
@Component({
  selector: 'ay-dashboard-page',
  changeDetection: ChangeDetectionStrategy.OnPush,
  imports: [
    SkeletonModule,
    WidgetShell,
    MarketOverviewWidget,
    ActiveSignalsWidget,
    JobsWidget,
    KiteStatusWidget,
    WatchlistWidget,
    PaperPnlWidget,
  ],
  styles: `
    .grid {
      display: grid;
      grid-template-columns: repeat(12, 1fr);
      grid-auto-rows: minmax(0, auto);
      gap: 1rem;
    }
    .market {
      grid-column: span 12;
    }
    .signals {
      grid-column: span 5;
    }
    .jobs {
      grid-column: span 4;
    }
    .kite {
      grid-column: span 3;
    }
    .watchlist {
      grid-column: span 6;
      min-height: 18rem;
    }
    .paper {
      grid-column: span 6;
    }
    @media (max-width: 1439px) {
      .signals,
      .jobs,
      .kite,
      .watchlist,
      .paper {
        grid-column: span 6;
      }
    }
    @media (max-width: 1023px) {
      .market,
      .signals,
      .jobs,
      .kite,
      .watchlist,
      .paper {
        grid-column: span 12;
      }
    }
  `,
  template: `
    <h1 class="ay-sr-only">Dashboard</h1>
    <div class="grid">
      <div class="market">
        <ay-widget title="Market Overview" widgetId="market">
          <ay-market-overview-widget />
        </ay-widget>
      </div>

      <div class="signals">
        <ay-widget title="Active Signals" widgetId="signals">
          <ay-active-signals-widget />
        </ay-widget>
      </div>

      <div class="jobs">
        <ay-widget title="Jobs" widgetId="jobs">
          <ay-jobs-widget />
        </ay-widget>
      </div>

      <div class="kite">
        <ay-widget title="Kite Status" widgetId="kite">
          <ay-kite-status-widget />
        </ay-widget>
      </div>

      <div class="paper">
        <ay-widget title="Paper P&L" widgetId="paper">
          <ay-paper-pnl-widget />
        </ay-widget>
      </div>

      <div class="watchlist">
        @defer (on viewport) {
          <ay-widget title="Watchlist" widgetId="watchlist">
            <ay-watchlist-widget />
          </ay-widget>
        } @placeholder {
          <p-skeleton height="18rem" />
        }
      </div>
    </div>
  `,
})
export class DashboardPage {}
