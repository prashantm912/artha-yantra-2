import { Directive, ElementRef, effect, inject, input } from '@angular/core';

/**
 * Flashes a CSS pulse class whenever its bound value changes (E-3: price-change flashes are
 * CSS-class pulses keyed off signal updates, NOT row re-creation). Honors `prefers-reduced-motion`
 * via the keyframes in styles.scss. The first value is not flashed (initial render).
 */
@Directive({ selector: '[ayPulse]' })
export class PulseDirective {
  readonly ayPulse = input<unknown>();

  constructor() {
    const el = inject(ElementRef).nativeElement as HTMLElement;
    let first = true;
    effect(() => {
      this.ayPulse(); // track
      if (first) {
        first = false;
        return;
      }
      el.classList.remove('ay-pulse');
      void el.offsetWidth; // reflow so the animation re-triggers
      el.classList.add('ay-pulse');
    });
  }
}
