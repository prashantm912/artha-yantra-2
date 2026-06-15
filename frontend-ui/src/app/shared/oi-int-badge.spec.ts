import { Component, signal } from '@angular/core';
import { TestBed } from '@angular/core/testing';
import { beforeEach, describe, expect, it } from 'vitest';
import { type OiInterpretation } from '../core/oi-interpretation';
import { OiIntBadge } from './oi-int-badge';

@Component({
  imports: [OiIntBadge],
  template: `<ay-oi-int-badge [value]="value()" />`,
})
class Host {
  readonly value = signal<OiInterpretation | null>(null);
}

describe('OiIntBadge', () => {
  beforeEach(async () => {
    await TestBed.configureTestingModule({ imports: [Host] }).compileComponents();
  });

  async function textFor(value: OiInterpretation | null): Promise<string> {
    const fixture = TestBed.createComponent(Host);
    fixture.componentInstance.value.set(value);
    await fixture.whenStable();
    return (fixture.nativeElement as HTMLElement).textContent ?? '';
  }

  it('names each of the four states (the label, not the colour, is the cue)', async () => {
    expect(await textFor('LONG_BUILDUP')).toContain('Long Buildup');
    expect(await textFor('SHORT_BUILDUP')).toContain('Short Buildup');
    expect(await textFor('SHORT_COVERING')).toContain('Short Covering');
    expect(await textFor('LONG_UNWINDING')).toContain('Long Unwinding');
  });

  it('renders an accessible em-dash when there is no interpretation', async () => {
    const text = await textFor(null);
    expect(text).toContain('—');
    expect(text).toContain('no interpretation');
  });
});
