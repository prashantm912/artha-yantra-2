import { ChangeDetectionStrategy, Component, computed, input } from '@angular/core';

/**
 * In-cell horizontal magnitude bar (oipulse data-bar archetype): a tinted background fill whose
 * width is `|value| / max` of the cell, with the pre-formatted label on top. Sign-neutral by
 * default; callers pass `tone="bull"|"bear"` for signed columns (e.g. OI change). The bar width is
 * a magnitude only — the colour conveys direction.
 */
@Component({
  selector: 'ay-data-bar',
  changeDetection: ChangeDetectionStrategy.OnPush,
  template: `<span class="bar" [class]="tone()" [style.--ay-bar-w.%]="pct()">
    <span class="lbl">{{ label() }}</span>
  </span>`,
  styles: `
    .bar {
      position: relative;
      display: block;
      overflow: hidden;
      padding: 0 0.4rem;
      text-align: right;
      font-variant-numeric: tabular-nums;
    }
    .bar::before {
      content: '';
      position: absolute;
      inset: 0 auto 0 0;
      width: var(--ay-bar-w, 0%);
    }
    .bar.neutral::before {
      background: color-mix(in srgb, var(--ay-text-muted) 22%, transparent);
    }
    .bar.bull::before {
      background: color-mix(in srgb, var(--ay-bull) 22%, transparent);
    }
    .bar.bear::before {
      background: color-mix(in srgb, var(--ay-bear) 22%, transparent);
    }
    .lbl {
      position: relative;
    }
  `,
})
export class DataBar {
  readonly value = input.required<number>();
  readonly max = input.required<number>();
  readonly label = input<string>('');
  readonly tone = input<'bull' | 'bear' | 'neutral'>('neutral');

  protected readonly pct = computed(() => {
    const max = this.max();
    if (max <= 0) {
      return 0;
    }
    return Math.min(100, (Math.abs(this.value()) / max) * 100);
  });
}
