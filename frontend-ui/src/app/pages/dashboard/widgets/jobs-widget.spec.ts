import { TestBed } from '@angular/core/testing';
import { signal } from '@angular/core';
import { describe, expect, it, vi, beforeEach } from 'vitest';
import { JobsWidget } from './jobs-widget';
import { JobsStore, type JobDto } from '../../../stores/jobs.store';

/** Fake JobsStore: just the slice the widget reads/calls. */
const cancel = vi.fn();
const active = signal<JobDto[]>([]);

describe('JobsWidget', () => {
  beforeEach(() => {
    cancel.mockClear();
    active.set([
      {
        jobId: 'job-12345678',
        kind: 'BACKTEST',
        status: 'running',
        progress: 42,
        createdAt: '2026-06-13T10:00:00+05:30',
      },
    ]);
    TestBed.configureTestingModule({
      imports: [JobsWidget],
      providers: [{ provide: JobsStore, useValue: { active, cancel } }],
    });
  });

  async function render(): Promise<HTMLElement> {
    const fixture = TestBed.createComponent(JobsWidget);
    await fixture.whenStable();
    return fixture.nativeElement as HTMLElement;
  }

  it('renders a row per active job with a progress bar', async () => {
    const html = await render();
    expect(html.textContent).toContain('job-1234');
    expect(html.querySelector('p-progressbar')).toBeTruthy();
  });

  it('the cancel button invokes store.cancel with the jobId', async () => {
    const html = await render();
    const button = html.querySelector('p-button button') as HTMLButtonElement;
    button.click();
    expect(cancel).toHaveBeenCalledWith('job-12345678');
  });

  it('shows the empty state when nothing is active', async () => {
    active.set([]);
    const html = await render();
    expect(html.textContent).toContain('No jobs running');
  });
});
