import { ChangeDetectionStrategy, Component, inject } from '@angular/core';
import { ButtonModule } from 'primeng/button';
import { ProgressBarModule } from 'primeng/progressbar';
import { TagModule } from 'primeng/tag';
import { JobsStore, type JobStatus } from '../../../stores/jobs.store';

/**
 * Jobs widget (E-2): the running/queued backtests & sweeps with live progress bars (driven by the
 * `jobs.progress` topic — no polling) and a cancel button. Empty when nothing is in flight.
 */
@Component({
  selector: 'ay-jobs-widget',
  changeDetection: ChangeDetectionStrategy.OnPush,
  imports: [ButtonModule, ProgressBarModule, TagModule],
  styles: `
    ul {
      list-style: none;
      margin: 0;
      padding: 0;
      display: flex;
      flex-direction: column;
      gap: 0.5rem;
    }
    li {
      display: grid;
      grid-template-columns: 1fr auto;
      gap: 0.4rem 0.6rem;
      align-items: center;
    }
    .meta {
      display: flex;
      gap: 0.4rem;
      align-items: center;
      font-size: 0.8rem;
      color: var(--ay-text-muted);
    }
    .bar {
      grid-column: 1 / 3;
    }
    .empty {
      color: var(--ay-text-muted);
      font-size: 0.85rem;
    }
  `,
  template: `
    @if (store.active().length) {
      <ul>
        @for (j of store.active(); track j.jobId) {
          <li>
            <span class="meta">
              <p-tag [value]="j.kind" severity="info" />
              <span>{{ j.jobId.slice(0, 8) }}</span>
              <p-tag [value]="j.status" [severity]="severity(j.status)" />
            </span>
            <p-button
              size="small"
              [text]="true"
              severity="danger"
              icon="pi pi-times"
              [ariaLabel]="'Cancel job ' + j.jobId"
              [disabled]="j.status === 'cancelling'"
              (onClick)="store.cancel(j.jobId)"
            />
            <p-progressbar class="bar" [value]="j.progress" [showValue]="true" />
          </li>
        }
      </ul>
    } @else {
      <p class="empty">No jobs running.</p>
    }
  `,
})
export class JobsWidget {
  protected readonly store = inject(JobsStore);

  protected severity(status: JobStatus): 'info' | 'warn' | 'success' | 'danger' | 'secondary' {
    switch (status) {
      case 'running':
        return 'info';
      case 'queued':
        return 'secondary';
      case 'cancelling':
        return 'warn';
      case 'completed':
        return 'success';
      default:
        return 'danger';
    }
  }
}
