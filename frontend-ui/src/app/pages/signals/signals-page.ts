import { ChangeDetectionStrategy, Component, inject } from '@angular/core';
import { FormsModule } from '@angular/forms';
import { ButtonModule } from 'primeng/button';
import { InputTextModule } from 'primeng/inputtext';
import { SelectModule } from 'primeng/select';
import { TableModule } from 'primeng/table';
import { TagModule } from 'primeng/tag';
import { formatDecimal } from '../../core/decimal';
import { SignalsStore, type SignalDto } from '../../stores/signals.store';
import { ReasoningBreakdownPanel } from './reasoning-breakdown-panel';

/**
 * /signals (C-2.24/C-2.26): live feed + history in one virtualized table, click-through to the
 * reasoning breakdown. The MVP surface — a published EMA-crossover strategy's call lands here
 * over STOMP with its per-indicator score breakdown.
 */
@Component({
  selector: 'ay-signals-page',
  changeDetection: ChangeDetectionStrategy.OnPush,
  imports: [
    FormsModule,
    TableModule,
    TagModule,
    ButtonModule,
    SelectModule,
    InputTextModule,
    ReasoningBreakdownPanel,
  ],
  styles: `
    .layout {
      display: grid;
      grid-template-columns: minmax(0, 1.4fr) minmax(20rem, 1fr);
      gap: 1rem;
      height: 100%;
    }
    .filters {
      display: flex;
      gap: 0.6rem;
      margin-bottom: 0.8rem;
      align-items: center;
    }
    .detail {
      border: 1px solid var(--ay-border);
      border-radius: 10px;
      background: var(--ay-surface-1);
      padding: 1rem;
      overflow: auto;
    }
    .placeholder {
      color: var(--ay-text-muted);
      display: grid;
      place-items: center;
      height: 100%;
    }
    .price {
      font-variant-numeric: tabular-nums;
    }
    .meta {
      color: var(--ay-text-muted);
      font-size: 0.8rem;
      margin-bottom: 0.8rem;
      overflow-wrap: anywhere;
    }
  `,
  template: `
    <div class="layout">
      <section>
        <h1 class="ay-sr-only">Live signals</h1>
        <div class="filters">
          <p-select
            [options]="statusOptions"
            [ngModel]="store.statusFilter()"
            (ngModelChange)="onStatusFilter($event)"
            placeholder="Status"
            [showClear]="true"
          />
          <input
            pInputText
            placeholder="Symbol filter"
            [ngModel]="store.symbolFilter()"
            (ngModelChange)="store.setSymbolFilter($event)"
          />
          <p-button [text]="true" icon="pi pi-refresh" ariaLabel="Reload" (onClick)="store.load()" />
        </div>
        <p-table
          [value]="store.filtered()"
          [scrollable]="true"
          scrollHeight="calc(100vh - 12rem)"
          [virtualScroll]="true"
          [virtualScrollItemSize]="44"
          selectionMode="single"
          dataKey="id"
          [loading]="store.loading()"
          (onRowSelect)="onRowSelect($event)"
        >
          <ng-template #header>
            <tr>
              <th>Time</th>
              <th>Strategy</th>
              <th>Instrument</th>
              <th>Type</th>
              <th>Entry</th>
              <th>Score</th>
              <th>Status</th>
            </tr>
          </ng-template>
          <ng-template #body let-signal>
            <tr [pSelectableRow]="signal" style="height: 44px">
              <td>{{ signal.generatedAt.slice(11, 19) }}</td>
              <td>{{ signal.strategyId ?? signal.strategyVersionId?.slice(0, 8) }}</td>
              <td>{{ signal.exchange }}:{{ signal.tradingsymbol }}</td>
              <td>
                <p-tag
                  [severity]="signal.signalType === 'ENTRY' ? 'success' : 'warn'"
                  [value]="signal.signalType + ' ' + signal.side"
                />
              </td>
              <td class="price">{{ signal.entryPrice ? price(signal.entryPrice) : '—' }}</td>
              <td class="price">{{ score(signal.compositeScore) }}</td>
              <td>
                <p-tag [severity]="statusSeverity(signal.status)" [value]="signal.status" />
              </td>
            </tr>
          </ng-template>
          <ng-template #emptymessage>
            <tr>
              <td colspan="7">No signals yet — publish a strategy and let the mock feed run.</td>
            </tr>
          </ng-template>
        </p-table>
      </section>

      <aside class="detail">
        @if (store.selected(); as signal) {
          <div class="meta">
            #{{ signal.id }} · v{{ signal.version ?? '?' }} · checksum
            {{ signal.checksum?.slice(0, 12) ?? 'n/a' }}… · SL
            {{ signal.stopLoss ? price(signal.stopLoss) : '—' }} · target
            {{ signal.target ? price(signal.target) : '—' }}
            <div>
              <p-button
                size="small"
                label="Taken"
                icon="pi pi-check"
                [text]="true"
                (onClick)="store.markTaken(signal.id)"
              />
              <p-button
                size="small"
                label="Dismiss"
                icon="pi pi-times"
                severity="danger"
                [text]="true"
                (onClick)="store.dismiss(signal.id)"
              />
            </div>
          </div>
          <ay-reasoning-breakdown [breakdown]="signal.scoreBreakdown" />
        } @else {
          <div class="placeholder">Select a signal to see its reasoning.</div>
        }
      </aside>
    </div>
  `,
})
export class SignalsPage {
  protected readonly store = inject(SignalsStore);

  protected readonly statusOptions = ['ACTIVE', 'EXPIRED', 'TAKEN', 'DISMISSED'];

  protected onStatusFilter(status: string | null): void {
    this.store.setStatusFilter(status);
    this.store.load();
  }

  protected onRowSelect(event: { data?: SignalDto | SignalDto[] }): void {
    const row = Array.isArray(event.data) ? event.data[0] : event.data;
    if (row) {
      this.store.select(row.id);
    }
  }

  protected price(value: string): string {
    return formatDecimal(value, 2);
  }

  protected score(value: string): string {
    return formatDecimal(value, 4);
  }

  protected statusSeverity(status: string): 'success' | 'secondary' | 'info' | 'danger' {
    switch (status) {
      case 'ACTIVE':
        return 'success';
      case 'TAKEN':
        return 'info';
      case 'DISMISSED':
        return 'danger';
      default:
        return 'secondary';
    }
  }
}
