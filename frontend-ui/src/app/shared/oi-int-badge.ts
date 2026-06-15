import { ChangeDetectionStrategy, Component, computed, input } from '@angular/core';
import { TagModule } from 'primeng/tag';
import { TooltipModule } from 'primeng/tooltip';
import { type OiInterpretation, oiIntMeta } from '../core/oi-interpretation';

/**
 * The flagship oipulse OI-Interpretation badge (primitive #1): a 4-state PrimeNG `p-tag` whose label
 * (not just its colour) names the state, so it is not colour-only. The price/OI direction breakdown
 * rides as a tooltip. Renders an accessible em-dash when no interpretation is available (e.g. a
 * single captured bucket → no interval delta yet).
 */
@Component({
  selector: 'ay-oi-int-badge',
  changeDetection: ChangeDetectionStrategy.OnPush,
  imports: [TagModule, TooltipModule],
  template: `
    @if (meta(); as m) {
      <p-tag [severity]="m.severity" [value]="m.label" [pTooltip]="m.arrow" />
    } @else {
      <span aria-hidden="true">—</span>
      <span class="ay-sr-only">no interpretation</span>
    }
  `,
})
export class OiIntBadge {
  readonly value = input<OiInterpretation | null>(null);
  protected readonly meta = computed(() => {
    const v = this.value();
    return v ? oiIntMeta(v) : null;
  });
}
