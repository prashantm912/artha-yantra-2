import { ChangeDetectionStrategy, Component, effect, inject } from '@angular/core';
import { FormsModule } from '@angular/forms';
import { TableModule } from 'primeng/table';
import { formatDecimal } from '../../core/decimal';
import { FiiDiiStore } from '../../stores/fii-dii.store';

/**
 * FII/DII (oipulse parity): index-level NSE EOD institutional activity — cash buy/sell/net,
 * FII index-futures long/short ratio, and participant-wise OI. Date-driven (own date picker);
 * reads the three /fii-dii feeds via {@link FiiDiiStore}, reloading when the date changes.
 */
@Component({
  selector: 'ay-fii-dii-page',
  changeDetection: ChangeDetectionStrategy.OnPush,
  imports: [FormsModule, TableModule],
  styles: `
    .bar {
      display: flex;
      align-items: center;
      gap: 0.6rem;
      margin-bottom: 0.8rem;
    }
    .date {
      height: 2.5rem;
      padding: 0 0.6rem;
      border: 1px solid var(--ay-border);
      border-radius: 6px;
      background: var(--ay-surface-1);
      color: var(--ay-text);
    }
    h2 {
      font-size: 1rem;
      margin: 1.1rem 0 0.4rem;
      color: var(--ay-text);
    }
    .num {
      text-align: right;
      font-variant-numeric: tabular-nums;
    }
  `,
  template: `
    <h1 class="ay-sr-only">FII / DII activity</h1>
    <div class="bar">
      <label for="fiidii-date">Date</label>
      <input
        id="fiidii-date"
        class="date"
        type="date"
        [ngModel]="store.from()"
        (ngModelChange)="store.setFrom($event)"
      />
    </div>

    <h2>Cash market</h2>
    <p-table [value]="store.cash()" [scrollable]="true" scrollHeight="22vh" dataKey="category">
      <ng-template #header>
        <tr>
          <th>Date</th>
          <th>Category</th>
          <th class="num">Buy</th>
          <th class="num">Sell</th>
          <th class="num">Net</th>
        </tr>
      </ng-template>
      <ng-template #body let-r>
        <tr>
          <td>{{ r.tradeDate }}</td>
          <td>{{ r.category }}</td>
          <td class="num">{{ dec(r.buyValue) }}</td>
          <td class="num">{{ dec(r.sellValue) }}</td>
          <td class="num">{{ dec(r.netValue) }}</td>
        </tr>
      </ng-template>
      <ng-template #emptymessage>
        <tr>
          <td colspan="5">No FII/DII cash data for this date.</td>
        </tr>
      </ng-template>
    </p-table>

    <h2>FII index-futures long / short</h2>
    <p-table
      [value]="store.longShort()"
      [scrollable]="true"
      scrollHeight="22vh"
      dataKey="tradeDate"
    >
      <ng-template #header>
        <tr>
          <th>Date</th>
          <th class="num">Long</th>
          <th class="num">Short</th>
          <th class="num">L/S ratio</th>
        </tr>
      </ng-template>
      <ng-template #body let-r>
        <tr>
          <td>{{ r.tradeDate }}</td>
          <td class="num">{{ num(r.fiiLong) }}</td>
          <td class="num">{{ num(r.fiiShort) }}</td>
          <td class="num">{{ dec(r.ratio) }}</td>
        </tr>
      </ng-template>
      <ng-template #emptymessage>
        <tr>
          <td colspan="4">No participant OI for this date.</td>
        </tr>
      </ng-template>
    </p-table>

    <h2>Participant-wise OI</h2>
    <p-table
      [value]="store.participant()"
      [scrollable]="true"
      scrollHeight="28vh"
      dataKey="clientType"
    >
      <ng-template #header>
        <tr>
          <th>Client</th>
          <th class="num">Fut idx long</th>
          <th class="num">Fut idx short</th>
          <th class="num">Opt idx CE long</th>
          <th class="num">Opt idx PE long</th>
          <th class="num">Total long</th>
          <th class="num">Total short</th>
        </tr>
      </ng-template>
      <ng-template #body let-r>
        <tr>
          <td>{{ r.clientType }}</td>
          <td class="num">{{ num(r.futureIndexLong) }}</td>
          <td class="num">{{ num(r.futureIndexShort) }}</td>
          <td class="num">{{ num(r.optionIndexCallLong) }}</td>
          <td class="num">{{ num(r.optionIndexPutLong) }}</td>
          <td class="num">{{ num(r.totalLongContracts) }}</td>
          <td class="num">{{ num(r.totalShortContracts) }}</td>
        </tr>
      </ng-template>
      <ng-template #emptymessage>
        <tr>
          <td colspan="7">No participant OI for this date.</td>
        </tr>
      </ng-template>
    </p-table>
  `,
})
export class FiiDiiPage {
  protected readonly store = inject(FiiDiiStore);

  private readonly reload = effect(() => {
    this.store.from();
    this.store.to();
    this.store.load();
  });

  protected dec(value: string | null | undefined): string {
    return value ? formatDecimal(value, 2) : '—';
  }

  protected num(value: number | null | undefined): string {
    return value != null ? value.toLocaleString('en-IN') : '—';
  }
}
