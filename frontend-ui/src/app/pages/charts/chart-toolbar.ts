import { ChangeDetectionStrategy, Component, computed, inject, model, signal } from '@angular/core';
import { FormsModule } from '@angular/forms';
import { AutoCompleteModule, type AutoCompleteCompleteEvent } from 'primeng/autocomplete';
import { ButtonModule } from 'primeng/button';
import { InputNumberModule } from 'primeng/inputnumber';
import { SelectModule } from 'primeng/select';
import { SelectButtonModule } from 'primeng/selectbutton';
import { TagModule } from 'primeng/tag';
import { MarketStore, type Instrument } from '../../stores/market.store';
import { ChartStateStore } from './chart-state.store';
import { ChartIndicatorsService, type IndicatorMeta } from './chart-indicators.service';

/**
 * Chart toolbar (Phase 40C): interval picker (1m–1w, incl. candles_1w), instrument search, the
 * engine-overlay picker (registry-driven; registry defaults + period override), and the
 * "View as table" toggle. Drives the persisted ChartStateStore. Responsive: wraps under 768px.
 */
@Component({
  selector: 'ay-chart-toolbar',
  changeDetection: ChangeDetectionStrategy.OnPush,
  imports: [
    FormsModule,
    SelectButtonModule,
    AutoCompleteModule,
    SelectModule,
    InputNumberModule,
    ButtonModule,
    TagModule,
  ],
  styles: `
    .bar {
      display: flex;
      gap: 0.6rem;
      align-items: center;
      flex-wrap: wrap;
      margin-bottom: 0.6rem;
    }
    .overlays {
      display: flex;
      gap: 0.4rem;
      align-items: center;
      flex-wrap: wrap;
    }
    .chip {
      display: inline-flex;
      gap: 0.3rem;
      align-items: center;
      border: 1px solid var(--ay-border);
      border-radius: 14px;
      padding: 0.1rem 0.5rem;
      font-size: 0.8rem;
    }
    .spacer {
      flex: 1;
    }
  `,
  template: `
    <div class="bar">
      <p-autocomplete
        [suggestions]="market.searchResults()"
        (completeMethod)="search($event)"
        optionLabel="tradingsymbol"
        [placeholder]="state.symbol()"
        [(ngModel)]="picked"
        (onSelect)="pick()"
        ariaLabel="Instrument search"
      />
      <p-selectButton
        [options]="intervals"
        [ngModel]="state.interval()"
        (ngModelChange)="state.setInterval($event)"
        ariaLabelledBy="interval"
      />
      <p-select
        [options]="overlayChoices()"
        optionLabel="label"
        optionValue="id"
        [ngModel]="null"
        (ngModelChange)="addOverlay($event)"
        placeholder="Add overlay"
        ariaLabel="Add overlay"
      />
      <span class="spacer"></span>
      <p-button
        [label]="showTable() ? 'Chart' : 'View as table'"
        [icon]="showTable() ? 'pi pi-chart-bar' : 'pi pi-table'"
        [text]="true"
        (onClick)="showTable.set(!showTable())"
      />
    </div>
    @if (state.overlays().length) {
      <div class="overlays">
        @for (o of state.overlays(); track o.id) {
          <span class="chip">
            {{ o.id }}
            @if (periodOf(o.id) !== null) {
              <p-inputnumber
                [ngModel]="periodOf(o.id)"
                (ngModelChange)="state.updateParam(o.id, periodKey(o.id), $event)"
                [min]="1"
                inputStyleClass="w-4rem"
                ariaLabel="period"
              />
            }
            <p-button
              size="small"
              [text]="true"
              icon="pi pi-times"
              [ariaLabel]="'Remove ' + o.id"
              (onClick)="state.removeOverlay(o.id)"
            />
          </span>
        }
      </div>
    }
  `,
})
export class ChartToolbar {
  readonly showTable = model<boolean>(false);

  protected readonly state = inject(ChartStateStore);
  protected readonly market = inject(MarketStore);
  private readonly indicators = inject(ChartIndicatorsService);

  protected readonly intervals = ['1m', '5m', '15m', '1h', '1d', '1w'];
  private readonly registry = signal<IndicatorMeta[]>([]);
  protected picked: Instrument | string = '';

  /** Overlay picker choices = registry indicators that don't need a context instrument (v1). */
  protected readonly overlayChoices = computed(() =>
    this.registry()
      .filter((m) => !m.requiresContext)
      .map((m) => ({ id: m.id, label: m.id })),
  );

  constructor() {
    void this.indicators.registry().then((list) => this.registry.set(list));
  }

  protected search(event: AutoCompleteCompleteEvent): void {
    this.market.search(event.query);
  }

  protected pick(): void {
    const inst = this.picked;
    if (inst && typeof inst !== 'string') {
      this.state.setSymbol(`${inst.exchange}:${inst.tradingsymbol}`);
      this.picked = '';
    }
  }

  protected addOverlay(id: string | null): void {
    if (!id) {
      return;
    }
    const meta = this.registry().find((m) => m.id === id);
    const params = Object.fromEntries((meta?.params ?? []).map((p) => [p.name, p.defaultValue]));
    this.state.addOverlay(id, params);
  }

  private firstNumericParam(id: string): { name: string; value: number } | null {
    const overlay = this.state.overlays().find((o) => o.id === id);
    if (!overlay) {
      return null;
    }
    for (const [name, value] of Object.entries(overlay.params)) {
      if (typeof value === 'number') {
        return { name, value };
      }
    }
    return null;
  }

  protected periodOf(id: string): number | null {
    return this.firstNumericParam(id)?.value ?? null;
  }

  protected periodKey(id: string): string {
    return this.firstNumericParam(id)?.name ?? 'period';
  }
}
