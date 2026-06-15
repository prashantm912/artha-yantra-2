import { Component, signal } from '@angular/core';
import { TestBed } from '@angular/core/testing';
import { describe, expect, it } from 'vitest';
import { DataBar } from './data-bar';

@Component({
  imports: [DataBar],
  template: `<ay-data-bar [value]="value()" [max]="max()" [tone]="tone()" [label]="label()" />`,
})
class Host {
  readonly value = signal(50);
  readonly max = signal(100);
  readonly tone = signal<'bull' | 'bear' | 'neutral'>('neutral');
  readonly label = signal('50');
}

describe('DataBar', () => {
  async function render(setup?: (host: Host) => void): Promise<HTMLElement> {
    await TestBed.configureTestingModule({ imports: [Host] }).compileComponents();
    const fixture = TestBed.createComponent(Host);
    if (setup) {
      setup(fixture.componentInstance);
    }
    await fixture.whenStable();
    return fixture.nativeElement as HTMLElement;
  }

  it('sets the bar width to value/max percent and renders the label', async () => {
    const el = await render();
    const bar = el.querySelector('.bar') as HTMLElement;
    expect(bar.style.getPropertyValue('--ay-bar-w')).toBe('50%');
    expect(bar.classList.contains('neutral')).toBe(true);
    expect(bar.textContent?.trim()).toBe('50');
  });

  it('clamps width to 100% when value exceeds max and applies the tone class', async () => {
    const el = await render((host) => {
      host.value.set(250);
      host.tone.set('bear');
    });
    const bar = el.querySelector('.bar') as HTMLElement;
    expect(bar.style.getPropertyValue('--ay-bar-w')).toBe('100%');
    expect(bar.classList.contains('bear')).toBe(true);
  });

  it('renders a zero-width bar when max is non-positive', async () => {
    const el = await render((host) => host.max.set(0));
    const bar = el.querySelector('.bar') as HTMLElement;
    expect(bar.style.getPropertyValue('--ay-bar-w')).toBe('0%');
  });
});
