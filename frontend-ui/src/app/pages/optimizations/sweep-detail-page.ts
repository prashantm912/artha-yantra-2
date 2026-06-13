import { ChangeDetectionStrategy, Component, computed, inject, signal } from '@angular/core';
import { FormsModule } from '@angular/forms';
import { ActivatedRoute, RouterLink } from '@angular/router';
import { ButtonModule } from 'primeng/button';
import { DialogModule } from 'primeng/dialog';
import { SelectButtonModule } from 'primeng/selectbutton';
import { TableModule } from 'primeng/table';
import { TagModule } from 'primeng/tag';
import { MessageService } from 'primeng/api';
import { formatDecimal } from '../../core/decimal';
import { OptimizationsStore, type BestRow, type SortMode } from '../../stores/optimizations.store';
import { FoldPanel } from '../backtests/fold-panel';
import { SweepExplorer } from './sweep-explorer';

/**
 * /optimizations/:sweepId (Phase 39): the live sweep explorer + guard-aware leaderboard. Plateau
 * sort is the default (raw one click away — guard 4); pruned/failed trials are FLAGGED, never hidden
 * (guard 3); nsga2 shows a Pareto front (guard 5); per-trial folds (regime mix + guard-7
 * degradation) open in a drill-down. Promote → diff preview → new draft. See PHASE_GATES for the
 * leaderboard-column guard gaps the optimizer /best response does not yet surface.
 */
@Component({
  selector: 'ay-sweep-detail-page',
  changeDetection: ChangeDetectionStrategy.OnPush,
  imports: [
    FormsModule,
    RouterLink,
    TableModule,
    TagModule,
    ButtonModule,
    DialogModule,
    SelectButtonModule,
    SweepExplorer,
    FoldPanel,
  ],
  styles: `
    .head {
      display: flex;
      gap: 0.8rem;
      align-items: center;
      margin-bottom: 0.8rem;
      flex-wrap: wrap;
    }
    .spacer {
      flex: 1;
    }
    .params {
      font-family: monospace;
      font-size: 0.78rem;
      color: var(--ay-text-muted);
    }
    .mono {
      font-variant-numeric: tabular-nums;
    }
  `,
  providers: [MessageService],
  template: `
    <h1 class="ay-sr-only">Sweep explorer</h1>
    <div class="head">
      <strong>Sweep {{ sweepId().slice(0, 8) }}</strong>
      @if (store.status(); as s) {
        <p-tag [value]="s" severity="info" />
      }
      <span class="mono">{{ store.trialsCompleted() }}/{{ store.trialsTotal() }} trials</span>
      @if (store.bestSoFar() !== null) {
        <span class="mono">best {{ best(store.bestSoFar()) }}</span>
      }
      <span class="spacer"></span>
      <p-selectButton
        [options]="sortOptions"
        optionLabel="label"
        optionValue="value"
        [ngModel]="store.sort()"
        (ngModelChange)="store.setSort($event)"
        ariaLabelledBy="sort"
      />
    </div>

    <ay-sweep-explorer
      [trials]="store.trials()"
      [metric]="store.metric()"
      (selection)="filter.set($event)"
    />

    <h3>Leaderboard ({{ store.sort() }} sort)</h3>
    <p-table [value]="store.best()" dataKey="trialNumber">
      <ng-template #header>
        <tr>
          <th>Trial</th>
          <th>Objective ({{ store.metric() }})</th>
          <th>Plateau</th>
          <th>Params</th>
          <th><span class="ay-sr-only">Actions</span></th>
        </tr>
      </ng-template>
      <ng-template #body let-row>
        <tr>
          <td>#{{ row.trialNumber }}</td>
          <td class="mono">{{ num(row.objective) }}</td>
          <td class="mono">{{ num(row.plateauObjective) }}</td>
          <td class="params">{{ paramStr(row) }}</td>
          <td>
            @if (row.backtestRunId) {
              <p-button
                size="small"
                [text]="true"
                icon="pi pi-chart-line"
                ariaLabel="Results"
                [routerLink]="['/backtests', row.backtestRunId]"
              />
            }
            <p-button
              size="small"
              [text]="true"
              icon="pi pi-sitemap"
              ariaLabel="Folds"
              (onClick)="openFolds(row.trialNumber)"
            />
            <p-button size="small" [text]="true" label="Promote" (onClick)="openPromote(row)" />
          </td>
        </tr>
      </ng-template>
      <ng-template #emptymessage>
        <tr>
          <td colspan="5">No completed trials yet.</td>
        </tr>
      </ng-template>
    </p-table>

    <h3>All trials (pruned/failed flagged, never hidden)</h3>
    <p-table
      [value]="filteredTrials()"
      dataKey="trialNumber"
      [scrollable]="true"
      scrollHeight="20rem"
    >
      <ng-template #header>
        <tr>
          <th>Trial</th>
          <th>State</th>
          <th>Objective</th>
          <th>Params</th>
        </tr>
      </ng-template>
      <ng-template #body let-t>
        <tr>
          <td>#{{ t.trialNumber }}</td>
          <td><p-tag [value]="t.state" [severity]="stateSev(t.state)" /></td>
          <td class="mono">
            {{ t.objectiveValues ? num(t.objectiveValues[store.metric()]) : '—' }}
          </td>
          <td class="params">{{ paramStr(t) }}</td>
        </tr>
      </ng-template>
    </p-table>

    <p-dialog
      header="Trial folds"
      [(visible)]="foldsVisible"
      [modal]="true"
      [style]="{ width: '60rem' }"
    >
      <ay-fold-panel [folds]="store.trialFolds()" [metric]="store.metric()" />
    </p-dialog>

    <p-dialog
      header="Promote trial → new draft"
      [(visible)]="promoteVisible"
      [modal]="true"
      [style]="{ width: '34rem' }"
    >
      @if (promoteRow(); as row) {
        <p>
          Trial #{{ row.trialNumber }} parameter values will be applied onto the source version as a
          new minor-bump draft:
        </p>
        <pre class="params">{{ paramStr(row) }}</pre>
      }
      <ng-template #footer>
        <p-button label="Cancel" [text]="true" (onClick)="promoteOpen.set(false)" />
        <p-button label="Create draft" icon="pi pi-check" (onClick)="doPromote()" />
      </ng-template>
    </p-dialog>
  `,
})
export class SweepDetailPage {
  protected readonly store = inject(OptimizationsStore);
  private readonly route = inject(ActivatedRoute);
  private readonly toast = inject(MessageService);

  protected readonly sweepId = signal<string>('');
  protected readonly filter = signal<number[]>([]);
  protected readonly sortOptions = [
    { label: 'Plateau', value: 'plateau' as SortMode },
    { label: 'Raw', value: 'raw' as SortMode },
  ];
  protected readonly foldsOpen = signal(false);
  protected readonly promoteOpen = signal(false);
  protected readonly promoteRow = signal<BestRow | null>(null);

  protected readonly filteredTrials = computed(() => {
    const sel = this.filter();
    const all = this.store.trials();
    return sel.length ? all.filter((t) => sel.includes(t.trialNumber)) : all;
  });

  protected get foldsVisible(): boolean {
    return this.foldsOpen();
  }
  protected set foldsVisible(v: boolean) {
    this.foldsOpen.set(v);
  }
  protected get promoteVisible(): boolean {
    return this.promoteOpen();
  }
  protected set promoteVisible(v: boolean) {
    this.promoteOpen.set(v);
  }

  constructor() {
    this.route.paramMap.subscribe((p) => {
      const id = p.get('sweepId') ?? '';
      this.sweepId.set(id);
      this.store.loadBest(id, this.store.sort());
      this.store.loadTrials(id);
    });
  }

  protected num(v: number | null | undefined): string {
    return v == null ? '—' : formatDecimal(String(v), 3);
  }
  protected best(v: number | null): string {
    return v == null ? '—' : formatDecimal(String(v), 3);
  }

  protected paramStr(row: { params: Record<string, unknown> }): string {
    return Object.entries(row.params)
      .map(([k, v]) => `${k}=${v}`)
      .join('  ');
  }

  protected stateSev(state: string): 'success' | 'info' | 'warn' | 'danger' {
    switch (state) {
      case 'COMPLETE':
        return 'success';
      case 'RUNNING':
        return 'info';
      case 'PRUNED':
        return 'warn';
      default:
        return 'danger';
    }
  }

  protected openFolds(trialNumber: number): void {
    this.store.loadTrialFolds(this.sweepId(), trialNumber);
    this.foldsOpen.set(true);
  }

  protected openPromote(row: BestRow): void {
    this.promoteRow.set(row);
    this.promoteOpen.set(true);
  }

  protected doPromote(): void {
    const row = this.promoteRow();
    if (!row) {
      return;
    }
    this.store.promote(this.sweepId(), row.trialNumber, (newVersion) => {
      this.promoteOpen.set(false);
      this.toast.add({
        severity: 'success',
        summary: 'Promoted',
        detail: `new draft v${newVersion}`,
      });
    });
  }
}
