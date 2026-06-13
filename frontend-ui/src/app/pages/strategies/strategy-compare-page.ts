import { ChangeDetectionStrategy, Component, inject, signal } from '@angular/core';
import { FormsModule } from '@angular/forms';
import { HttpClient } from '@angular/common/http';
import { MessageModule } from 'primeng/message';
import { SelectModule } from 'primeng/select';
import { StrategiesStore } from '../../stores/strategies.store';
import { MonacoDiff } from './monaco-diff';

interface VersionDetail {
  version: string;
  configYaml: string;
}

/**
 * /strategies/compare (Phase 37, E-8): two strategy versions' configs side-by-side (Monaco diff).
 * The "latest backtest metrics" column is omitted in v1 — the backend exposes no
 * latest-backtest-per-version query (noted in PHASE_GATES).
 */
@Component({
  selector: 'ay-strategy-compare-page',
  changeDetection: ChangeDetectionStrategy.OnPush,
  imports: [FormsModule, SelectModule, MessageModule, MonacoDiff],
  styles: `
    .pickers {
      display: grid;
      grid-template-columns: 1fr 1fr;
      gap: 1rem;
      margin-bottom: 0.8rem;
    }
    .pick {
      display: flex;
      gap: 0.5rem;
      align-items: center;
    }
    .diffwrap {
      height: 30rem;
    }
  `,
  template: `
    <h1 class="ay-sr-only">Compare strategy versions</h1>
    <div class="pickers">
      <div class="pick">
        <p-select
          [options]="strategyOptions()"
          optionLabel="name"
          optionValue="id"
          [(ngModel)]="idA"
          (ngModelChange)="loadVersions('A', $event)"
          placeholder="Strategy A"
          ariaLabel="Strategy A"
        />
        <p-select
          [options]="versionsA()"
          [(ngModel)]="verA"
          (ngModelChange)="fetch('A')"
          placeholder="version"
          ariaLabel="Version A"
        />
      </div>
      <div class="pick">
        <p-select
          [options]="strategyOptions()"
          optionLabel="name"
          optionValue="id"
          [(ngModel)]="idB"
          (ngModelChange)="loadVersions('B', $event)"
          placeholder="Strategy B"
          ariaLabel="Strategy B"
        />
        <p-select
          [options]="versionsB()"
          [(ngModel)]="verB"
          (ngModelChange)="fetch('B')"
          placeholder="version"
          ariaLabel="Version B"
        />
      </div>
    </div>
    <p-message
      severity="secondary"
      text="Latest-backtest metrics per version are not yet available from the backend; configs only."
    />
    <div class="diffwrap">
      <ay-monaco-diff [original]="yamlA()" [modified]="yamlB()" />
    </div>
  `,
})
export class StrategyComparePage {
  protected readonly store = inject(StrategiesStore);
  private readonly http = inject(HttpClient);

  protected idA: string | null = null;
  protected idB: string | null = null;
  protected verA: string | null = null;
  protected verB: string | null = null;

  protected readonly versionsA = signal<string[]>([]);
  protected readonly versionsB = signal<string[]>([]);
  protected readonly yamlA = signal<string>('');
  protected readonly yamlB = signal<string>('');

  protected readonly strategyOptions = this.store.list;

  constructor() {
    this.store.loadList();
  }

  protected loadVersions(side: 'A' | 'B', id: string): void {
    this.http.get<{ items: { version: string }[] }>(`/api/v1/strategies/${id}/versions`).subscribe({
      next: (page) => {
        const list = (page.items ?? []).map((v) => v.version);
        (side === 'A' ? this.versionsA : this.versionsB).set(list);
      },
      error: () => undefined,
    });
  }

  protected fetch(side: 'A' | 'B'): void {
    const id = side === 'A' ? this.idA : this.idB;
    const ver = side === 'A' ? this.verA : this.verB;
    if (!id || !ver) {
      return;
    }
    this.http.get<VersionDetail>(`/api/v1/strategies/${id}/versions/${ver}`).subscribe({
      next: (d) => (side === 'A' ? this.yamlA : this.yamlB).set(d.configYaml ?? ''),
      error: () => undefined,
    });
  }
}
