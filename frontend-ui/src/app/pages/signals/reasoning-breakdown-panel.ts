import { NgTemplateOutlet } from '@angular/common';
import { ChangeDetectionStrategy, Component, computed, input } from '@angular/core';
import { TagModule } from 'primeng/tag';
import { TooltipModule } from 'primeng/tooltip';
import { formatDecimal } from '../../core/decimal';
import type { GateNodeDto, ScoreBreakdownDto } from '../../stores/signals.store';

interface ContributionRow {
  alias: string;
  name: string;
  timeframe: string;
  score: string;
  weight: string;
  contribution: string;
  percentOfComposite: number;
  optional: boolean;
  activated: boolean;
  activationReason: string;
  rawValue: string;
}

/**
 * Renders the FROZEN Phase 20 contract (C-2.6/C-2.26): gate checklist with actual operand
 * values, stacked contribution bar, composite-vs-threshold gauge, optional-activation badges.
 * Obeys (and visually demonstrates) the renderer invariant
 * {@code composite = Σ contributions / weightDenominator}; derived percentages are computed(),
 * memoized, never stored.
 */
@Component({
  selector: 'ay-reasoning-breakdown',
  changeDetection: ChangeDetectionStrategy.OnPush,
  imports: [NgTemplateOutlet, TagModule, TooltipModule],
  styles: `
    .panel {
      display: flex;
      flex-direction: column;
      gap: 1rem;
    }
    h3 {
      margin: 0 0 0.4rem;
      font-size: 0.95rem;
      color: var(--ay-text);
    }
    .gauge {
      position: relative;
      height: 14px;
      border-radius: 7px;
      background: var(--ay-surface-2);
      overflow: hidden;
    }
    .gauge .fill {
      height: 100%;
      background: var(--ay-bull);
    }
    .gauge .fill.miss {
      background: var(--ay-warn);
    }
    .gauge .threshold {
      position: absolute;
      top: -2px;
      bottom: -2px;
      width: 2px;
      background: var(--ay-bear);
    }
    .gauge-caption {
      font-size: 0.8rem;
      color: var(--ay-text-muted);
      margin-top: 0.3rem;
    }
    .stack {
      display: flex;
      height: 18px;
      border-radius: 6px;
      overflow: hidden;
      background: var(--ay-surface-2);
    }
    .stack .seg {
      height: 100%;
    }
    .rows {
      display: flex;
      flex-direction: column;
      gap: 0.35rem;
      font-size: 0.85rem;
    }
    .row {
      display: grid;
      grid-template-columns: 1fr auto auto auto;
      gap: 0.6rem;
      align-items: center;
      padding: 0.35rem 0.5rem;
      border: 1px solid var(--ay-border);
      border-radius: 8px;
    }
    .muted {
      color: var(--ay-text-muted);
    }
    ul.gate {
      list-style: none;
      margin: 0;
      padding-left: 1rem;
      display: flex;
      flex-direction: column;
      gap: 0.3rem;
      font-size: 0.85rem;
    }
    .leaf-pass {
      color: var(--ay-bull);
    }
    .leaf-fail {
      color: var(--ay-bear);
    }
    code {
      background: var(--ay-surface-2);
      padding: 0.05rem 0.35rem;
      border-radius: 4px;
    }
  `,
  template: `
    <div class="panel">
      <section>
        <h3>Composite vs threshold</h3>
        <div class="gauge">
          <div class="fill" [class.miss]="!breakdown().passed" [style.width.%]="compositePct()"></div>
          <div class="threshold" [style.left.%]="thresholdPct()"></div>
        </div>
        <div class="gauge-caption">
          composite {{ fmt(breakdown().composite) }} / threshold {{ fmt(breakdown().threshold) }}
          — required-only {{ fmt(breakdown().requiredComposite) }}, weight denominator
          {{ fmt(breakdown().weightDenominator) }} (invariant: composite = Σ contributions ÷ denominator)
        </div>
      </section>

      <section>
        <h3>Contributions (w·s)</h3>
        <div class="stack" aria-label="Stacked indicator contributions">
          @for (row of rows(); track row.alias) {
            @if (row.activated) {
              <div
                class="seg"
                [style.width.%]="row.percentOfComposite"
                [style.background]="segColor($index)"
                [pTooltip]="row.alias + ': ' + row.contribution"
              ></div>
            }
          }
        </div>
        <div class="rows">
          @for (row of rows(); track row.alias) {
            <div class="row">
              <span>
                <strong>{{ row.alias }}</strong>
                <span class="muted"> {{ row.name }} · {{ row.timeframe }} · raw {{ row.rawValue }}</span>
              </span>
              <span>s {{ row.score }} × w {{ row.weight }} = {{ row.contribution }}</span>
              <p-tag
                [severity]="reasonSeverity(row.activationReason)"
                [value]="row.activationReason"
              />
              @if (row.optional) {
                <p-tag severity="secondary" value="optional" />
              } @else {
                <span class="muted">required</span>
              }
            </div>
          }
        </div>
      </section>

      <section>
        <h3>Gate checklist</h3>
        <ul class="gate">
          <ng-container *ngTemplateOutlet="gateNode; context: { $implicit: breakdown().gate }" />
        </ul>
        <ng-template #gateNode let-node>
          <li>
            <span [class]="node.passed ? 'leaf-pass' : 'leaf-fail'">
              {{ node.passed ? '✔' : '✘' }}
            </span>
            @if (node.rule) {
              <code>{{ node.rule }}</code>
            } @else {
              <strong>{{ node.kind }}</strong>
            }
            @if (node.operands) {
              <span class="muted"> {{ operandText(node) }}</span>
            }
            @if (node.children?.length) {
              <ul class="gate">
                @for (child of node.children; track $index) {
                  <ng-container *ngTemplateOutlet="gateNode; context: { $implicit: child }" />
                }
              </ul>
            }
          </li>
        </ng-template>
      </section>
    </div>
  `,
})
export class ReasoningBreakdownPanel {
  readonly breakdown = input.required<ScoreBreakdownDto>();

  private static readonly SEGMENT_COLORS = [
    'var(--ay-accent)',
    'var(--ay-bull)',
    'var(--ay-warn)',
    'var(--ay-chart-crosshair)',
  ];

  protected readonly compositePct = computed(() =>
    Math.min(100, Number(this.breakdown().composite) * 100),
  );
  protected readonly thresholdPct = computed(() =>
    Math.min(100, Number(this.breakdown().threshold) * 100),
  );

  /** Derived rows — percent widths normalize activated contributions to the composite total. */
  protected readonly rows = computed<ContributionRow[]>(() => {
    const breakdown = this.breakdown();
    const total = breakdown.indicators
      .filter((entry) => entry.activated)
      .reduce((sum, entry) => sum + Number(entry.contribution), 0);
    return breakdown.indicators.map((entry) => ({
      alias: entry.alias,
      name: entry.name,
      timeframe: entry.timeframe,
      score: entry.score === null ? '—' : this.fmt(entry.score),
      weight: this.fmt(entry.weight),
      contribution: this.fmt(entry.contribution),
      percentOfComposite:
        entry.activated && total > 0 ? (Number(entry.contribution) / total) * 100 : 0,
      optional: entry.optional,
      activated: entry.activated,
      activationReason: entry.activationReason,
      rawValue: entry.rawValue === null ? '—' : String(entry.rawValue),
    }));
  });

  protected fmt(value: number | string): string {
    return formatDecimal(String(value), 4);
  }

  protected segColor(index: number): string {
    return ReasoningBreakdownPanel.SEGMENT_COLORS[
      index % ReasoningBreakdownPanel.SEGMENT_COLORS.length
    ];
  }

  protected reasonSeverity(reason: string): 'success' | 'info' | 'warn' | 'secondary' {
    switch (reason) {
      case 'REQUIRED':
        return 'info';
      case 'ACTIVATED':
        return 'success';
      case 'MARGIN_NOT_MET':
        return 'warn';
      default:
        return 'secondary';
    }
  }

  protected operandText(node: GateNodeDto): string {
    return Object.entries(node.operands ?? {})
      .map(([name, value]) => `${name}=${value ?? 'n/a'}`)
      .join(', ');
  }
}
