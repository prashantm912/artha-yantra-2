import { ChangeDetectionStrategy, Component, computed, input } from '@angular/core';
import { TagModule } from 'primeng/tag';
import { TooltipModule } from 'primeng/tooltip';
import { formatDecimal } from '../core/decimal';
import { degradationBand } from './degradation';

/**
 * Guard-7 train→OOS degradation badge (E-12.3): a traffic-light p-tag with the difference value.
 * Reused by the publish stress advisory (Phase 37) and the leaderboard / fold panel (Phases 38/39).
 */
@Component({
  selector: 'ay-degradation-badge',
  changeDetection: ChangeDetectionStrategy.OnPush,
  imports: [TagModule, TooltipModule],
  template: `<p-tag
    [severity]="verdict().severity"
    [value]="text()"
    pTooltip="train − OOS Sharpe"
  />`,
})
export class DegradationBadge {
  readonly value = input<string | number | null>(null);
  readonly trainSharpe = input<string | number | null>(null);

  protected readonly verdict = computed(() => degradationBand(this.value(), this.trainSharpe()));
  protected readonly text = computed(() => {
    const v = this.verdict();
    if (v.band === 'na') {
      return v.label;
    }
    return `${v.label} (${formatDecimal(String(this.value()), 2)})`;
  });
}
