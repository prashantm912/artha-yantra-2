import { ChangeDetectionStrategy, Component, inject } from '@angular/core';
import { FormsModule } from '@angular/forms';
import { RouterLink } from '@angular/router';
import { ButtonModule } from 'primeng/button';
import { ProgressBarModule } from 'primeng/progressbar';
import { SelectModule } from 'primeng/select';
import { TableModule } from 'primeng/table';
import { TagModule } from 'primeng/tag';
import { JobsStore, type JobStatus } from '../../stores/jobs.store';

/**
 * /backtests/jobs (Phase 35, E-11 screen 4): every backtest/sweep job with live progress bars
 * (driven by the `jobs.progress` topic — no polling) and per-row cancel. The live sweep detail
 * (ECharts trial explorer) lands in Phase 39 atop the same store.
 */
@Component({
  selector: 'ay-jobs-page',
  changeDetection: ChangeDetectionStrategy.OnPush,
  imports: [
    FormsModule,
    RouterLink,
    TableModule,
    TagModule,
    ButtonModule,
    ProgressBarModule,
    SelectModule,
  ],
  styles: `
    .filters {
      display: flex;
      gap: 0.6rem;
      align-items: center;
      margin-bottom: 0.8rem;
    }
    .mono {
      font-family: var(--ay-mono, monospace);
      font-size: 0.85rem;
    }
    .bar {
      width: 9rem;
    }
  `,
  template: `
    <h1 class="ay-sr-only">Jobs</h1>
    <div class="filters">
      <p-select
        [options]="statusOptions"
        [ngModel]="store.statusFilter()"
        (ngModelChange)="store.setStatusFilter($event)"
        placeholder="Status"
        [showClear]="true"
      />
      <p-button [text]="true" icon="pi pi-refresh" ariaLabel="Reload" (onClick)="store.load()" />
    </div>
    <p-table
      [value]="store.jobs()"
      [scrollable]="true"
      scrollHeight="calc(100vh - 12rem)"
      [virtualScroll]="true"
      [virtualScrollItemSize]="46"
      dataKey="jobId"
      [loading]="store.loading()"
    >
      <ng-template #header>
        <tr>
          <th>Job</th>
          <th>Type</th>
          <th>Status</th>
          <th>Progress</th>
          <th>Created</th>
          <th></th>
        </tr>
      </ng-template>
      <ng-template #body let-job>
        <tr style="height: 46px">
          <td class="mono">{{ job.jobId.slice(0, 8) }}</td>
          <td><p-tag [value]="job.kind" severity="info" /></td>
          <td><p-tag [value]="job.status" [severity]="severity(job.status)" /></td>
          <td><p-progressbar class="bar" [value]="job.progress" [showValue]="true" /></td>
          <td>{{ job.createdAt?.slice(0, 19).replace('T', ' ') }}</td>
          <td>
            @if (job.kind === 'OPTIMIZATION') {
              <p-button
                size="small"
                [text]="true"
                icon="pi pi-chart-bar"
                [ariaLabel]="'Open sweep ' + job.jobId"
                [routerLink]="['/optimizations', job.jobId]"
              />
            }
            <p-button
              size="small"
              [text]="true"
              severity="danger"
              icon="pi pi-times"
              [ariaLabel]="'Cancel job ' + job.jobId"
              [disabled]="!cancellable(job.status)"
              (onClick)="store.cancel(job.jobId)"
            />
          </td>
        </tr>
      </ng-template>
      <ng-template #emptymessage>
        <tr>
          <td colspan="6">No jobs yet — launch a backtest or sweep from the runner.</td>
        </tr>
      </ng-template>
    </p-table>
  `,
})
export class JobsPage {
  protected readonly store = inject(JobsStore);
  protected readonly statusOptions = ['queued', 'running', 'completed', 'failed', 'cancelled'];

  protected cancellable(status: JobStatus): boolean {
    return status === 'queued' || status === 'running';
  }

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
