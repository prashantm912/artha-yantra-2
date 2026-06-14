import { ChangeDetectionStrategy, Component, inject, signal } from '@angular/core';
import { FormsModule } from '@angular/forms';
import { Router } from '@angular/router';
import { ButtonModule } from 'primeng/button';
import { CheckboxModule } from 'primeng/checkbox';
import { DialogModule } from 'primeng/dialog';
import { InputTextModule } from 'primeng/inputtext';
import { SelectModule } from 'primeng/select';
import { TableModule } from 'primeng/table';
import { TagModule } from 'primeng/tag';
import {
  StrategiesStore,
  type BacktestSummary,
  type StrategySummary,
} from '../../stores/strategies.store';

const STARTER_TEMPLATE = `schema: strategy-schema/v1
id: my-strategy
name: "My Strategy"
version: 1.0.0
universe:
  mode: explicit
  instruments:
    - { exchange: NSE, tradingsymbol: RELIANCE }
timeframes: { primary: 1d }
indicators:
  - { name: EMA, alias: ema_fast, timeframe: 1d, params: { period: 9 }, weight: 1.0 }
  - { name: EMA, alias: ema_slow, timeframe: 1d, params: { period: 21 }, weight: 1.0 }
entry_rules:
  direction: long
  gate:
    all:
      - crossover: { fast: ema_fast, slow: ema_slow }
  scoring: { threshold: 0.5 }
exit_rules:
  - { type: stop_loss, params: { basis: premium_pct, value: 50 } }
risk:
  position_sizing: { method: fixed_quantity, params: { quantity: 1 } }
  max_positions: 1
  session: { style: intraday }
`;

/**
 * /strategies (Phase 36, E-11 screen 1): published/draft/archived strategies with status, version
 * and tags. Create opens the editor with a starter template; Import loads pasted/file YAML into the
 * editor (the editor's server-`POST /validate` gates save). Row actions: edit, history.
 */
@Component({
  selector: 'ay-strategy-list-page',
  changeDetection: ChangeDetectionStrategy.OnPush,
  imports: [
    FormsModule,
    TableModule,
    TagModule,
    ButtonModule,
    CheckboxModule,
    InputTextModule,
    SelectModule,
    DialogModule,
  ],
  styles: `
    .toolbar {
      display: flex;
      gap: 0.6rem;
      align-items: center;
      margin-bottom: 0.8rem;
      flex-wrap: wrap;
    }
    .grow {
      flex: 1;
    }
    .tags {
      display: flex;
      gap: 0.3rem;
      flex-wrap: wrap;
    }
    .notify {
      display: flex;
      gap: 0.3rem;
      align-items: center;
    }
    .last-bt {
      display: flex;
      gap: 0.5rem;
      align-items: center;
      white-space: nowrap;
    }
    .spark {
      color: var(--p-primary-color);
    }
    textarea {
      width: 100%;
      min-height: 14rem;
      font-family: monospace;
      font-size: 0.85rem;
    }
  `,
  template: `
    <h1 class="ay-sr-only">Strategies</h1>
    <div class="toolbar">
      <input
        pInputText
        class="grow"
        placeholder="Search name / slug"
        [ngModel]="store.q()"
        (ngModelChange)="store.setQuery($event)"
      />
      <p-select
        [options]="statuses"
        [ngModel]="store.statusFilter()"
        (ngModelChange)="store.setStatusFilter($event)"
        placeholder="Status"
        [showClear]="true"
      />
      <p-button label="Create strategy" icon="pi pi-plus" (onClick)="createNew()" />
      <p-button
        label="Import YAML"
        icon="pi pi-upload"
        [outlined]="true"
        (onClick)="importOpen.set(true)"
      />
    </div>

    <p-table
      [value]="store.list()"
      dataKey="id"
      [loading]="store.loading()"
      [scrollable]="true"
      scrollHeight="calc(100vh - 13rem)"
    >
      <ng-template #header>
        <tr>
          <th>Name</th>
          <th>Status</th>
          <th>Version</th>
          <th>Last backtest</th>
          <th>Tags</th>
          <th>Notify</th>
          <th>Updated</th>
          <th><span class="ay-sr-only">Actions</span></th>
        </tr>
      </ng-template>
      <ng-template #body let-s>
        <tr>
          <td>{{ s.name }}</td>
          <td><p-tag [value]="s.status" [severity]="severity(s.status)" /></td>
          <td>{{ s.publishedVersion ?? s.currentVersion ?? '—' }}</td>
          <td>
            @if (summaryFor(s); as bt) {
              <div class="last-bt">
                <span>Sharpe {{ bt.sharpe ?? '—' }}</span>
                @if (sparkPoints(bt.equity); as pts) {
                  <svg class="spark" viewBox="0 0 80 22" width="80" height="22" aria-hidden="true">
                    <polyline
                      [attr.points]="pts"
                      fill="none"
                      stroke="currentColor"
                      stroke-width="1.5"
                    />
                  </svg>
                }
              </div>
            } @else {
              —
            }
          </td>
          <td>
            <span class="tags">
              @for (t of s.tags; track t) {
                <p-tag [value]="t" severity="secondary" />
              }
            </span>
          </td>
          <td>
            <span class="notify">
              <p-checkbox
                [ngModel]="s.notificationsEnabled ?? false"
                (ngModelChange)="
                  store.setNotifications(s.id, $event, s.notificationChannel ?? 'NTFY')
                "
                [binary]="true"
                [ariaLabel]="'Notifications for ' + s.name"
              />
              @if (s.notificationsEnabled) {
                <p-select
                  [options]="channels"
                  [ngModel]="s.notificationChannel ?? 'NTFY'"
                  (ngModelChange)="store.setNotifications(s.id, true, $event)"
                  ariaLabel="Channel"
                />
                <p-button
                  size="small"
                  [text]="true"
                  icon="pi pi-bell"
                  ariaLabel="Send test notification"
                  (onClick)="store.testNotification(s.id)"
                />
              }
            </span>
          </td>
          <td>{{ s.updatedAt ? s.updatedAt.slice(0, 10) : '—' }}</td>
          <td>
            <p-button
              size="small"
              [text]="true"
              icon="pi pi-pencil"
              ariaLabel="Edit"
              (onClick)="edit(s.id)"
            />
            <p-button
              size="small"
              [text]="true"
              icon="pi pi-history"
              ariaLabel="History"
              (onClick)="versions(s.id)"
            />
          </td>
        </tr>
      </ng-template>
      <ng-template #emptymessage>
        <tr>
          <td colspan="8">No strategies yet — create one to get started.</td>
        </tr>
      </ng-template>
    </p-table>

    <p-dialog
      header="Import YAML"
      [(visible)]="importVisible"
      [modal]="true"
      [style]="{ width: '40rem' }"
    >
      <input type="file" accept=".yaml,.yml" (change)="onFile($event)" />
      <textarea [(ngModel)]="importText" placeholder="…or paste strategy YAML here"></textarea>
      <ng-template #footer>
        <p-button label="Cancel" [text]="true" (onClick)="importOpen.set(false)" />
        <p-button
          label="Open in editor"
          icon="pi pi-arrow-right"
          [disabled]="!importText.trim()"
          (onClick)="importYaml()"
        />
      </ng-template>
    </p-dialog>
  `,
})
export class StrategyListPage {
  protected readonly store = inject(StrategiesStore);
  private readonly router = inject(Router);
  protected readonly statuses = ['draft', 'published', 'archived'];
  protected readonly channels = ['NTFY', 'TELEGRAM'];
  protected readonly importOpen = signal(false);
  protected importText = '';

  // p-dialog two-way visibility bridged to the signal
  protected get importVisible(): boolean {
    return this.importOpen();
  }
  protected set importVisible(v: boolean) {
    this.importOpen.set(v);
  }

  constructor() {
    this.store.loadList();
  }

  protected severity(status: string): 'success' | 'warn' | 'secondary' {
    return status === 'published' ? 'success' : status === 'archived' ? 'secondary' : 'warn';
  }

  /** The last-backtest summary for a row, keyed by its published-or-current version id. */
  protected summaryFor(s: StrategySummary): BacktestSummary | null {
    const id = s.publishedVersionId ?? s.currentVersionId;
    return id ? (this.store.summaries()[id] ?? null) : null;
  }

  /** A decorative `<polyline>` points string for the equity sparkline (display-only scaling). */
  protected sparkPoints(equity: string[]): string {
    if (!equity || equity.length < 2) {
      return '';
    }
    const nums = equity.map(Number);
    const min = Math.min(...nums);
    const span = Math.max(...nums) - min || 1;
    const w = 80;
    const h = 22;
    return nums
      .map((v, i) => {
        const x = (i / (nums.length - 1)) * w;
        const y = h - ((v - min) / span) * h;
        return `${x.toFixed(1)},${y.toFixed(1)}`;
      })
      .join(' ');
  }

  protected edit(id: string): void {
    void this.router.navigate(['/strategies', id, 'edit']);
  }

  protected versions(id: string): void {
    void this.router.navigate(['/strategies', id, 'versions']);
  }

  protected createNew(): void {
    this.store.startNewDraft(STARTER_TEMPLATE);
    void this.router.navigate(['/strategies', 'new', 'edit']);
  }

  protected onFile(event: Event): void {
    const file = (event.target as HTMLInputElement).files?.[0];
    if (file) {
      void file.text().then((text) => (this.importText = text));
    }
  }

  protected importYaml(): void {
    this.store.startNewDraft(this.importText);
    this.importOpen.set(false);
    void this.router.navigate(['/strategies', 'new', 'edit']);
  }
}
