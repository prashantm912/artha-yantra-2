import { ChangeDetectionStrategy, Component, computed, inject, signal } from '@angular/core';
import { FormsModule } from '@angular/forms';
import { ActivatedRoute } from '@angular/router';
import { ButtonModule } from 'primeng/button';
import { DialogModule } from 'primeng/dialog';
import { InputTextModule } from 'primeng/inputtext';
import { MessageService } from 'primeng/api';
import { SelectModule } from 'primeng/select';
import { TableModule } from 'primeng/table';
import { TagModule } from 'primeng/tag';
import { StrategiesStore } from '../../stores/strategies.store';
import { MonacoDiff } from './monaco-diff';
import { StressAdvisory } from './stress-advisory';

/**
 * /strategies/:id/versions (Phase 37, E-11 screen 6): immutable version timeline, structured diff
 * (`path: before → after`) above a Monaco side-by-side YAML diff, publish dialog with the S1C
 * advisory (publish never blocked), and rollback (copy-forward draft).
 */
@Component({
  selector: 'ay-versions-page',
  changeDetection: ChangeDetectionStrategy.OnPush,
  imports: [
    FormsModule,
    TableModule,
    TagModule,
    ButtonModule,
    SelectModule,
    InputTextModule,
    DialogModule,
    MonacoDiff,
    StressAdvisory,
  ],
  styles: `
    .toolbar {
      display: flex;
      gap: 0.6rem;
      align-items: center;
      margin-bottom: 0.8rem;
    }
    .spacer {
      flex: 1;
    }
    .layout {
      display: grid;
      grid-template-columns: minmax(20rem, 28rem) minmax(0, 1fr);
      gap: 1rem;
    }
    .compare {
      display: flex;
      gap: 0.5rem;
      align-items: center;
      margin-bottom: 0.6rem;
    }
    .structured {
      margin-bottom: 0.6rem;
      max-height: 9rem;
      overflow: auto;
      font-size: 0.83rem;
    }
    .op {
      font-family: monospace;
    }
    .diffwrap {
      height: 26rem;
    }
    .mono {
      font-family: monospace;
      font-size: 0.82rem;
    }
  `,
  template: `
    <h1 class="ay-sr-only">Strategy versions</h1>
    <div class="toolbar">
      <strong>{{ store.current()?.name ?? 'Strategy' }} — versions</strong>
      <span class="spacer"></span>
      <p-button
        label="Publish…"
        icon="pi pi-upload"
        [disabled]="!draftVersion()"
        (onClick)="openPublish()"
      />
    </div>

    <div class="layout">
      <section>
        <p-table [value]="store.versions()" dataKey="version">
          <ng-template #header>
            <tr>
              <th>Version</th>
              <th>Status</th>
              <th>Checksum</th>
              <th>Created</th>
              <th><span class="ay-sr-only">Actions</span></th>
            </tr>
          </ng-template>
          <ng-template #body let-v>
            <tr>
              <td>{{ v.version }}</td>
              <td><p-tag [value]="v.status" [severity]="sev(v.status)" /></td>
              <td class="mono">{{ v.checksum?.slice(0, 10) }}</td>
              <td>{{ v.createdAt ? v.createdAt.slice(0, 10) : '—' }}</td>
              <td>
                <p-button size="small" [text]="true" label="Diff" (onClick)="setFrom(v.version)" />
                <p-button
                  size="small"
                  [text]="true"
                  severity="warn"
                  label="Rollback"
                  (onClick)="rollback(v.version)"
                />
              </td>
            </tr>
          </ng-template>
        </p-table>
      </section>

      <section>
        <div class="compare">
          <span>Compare</span>
          <p-select
            [options]="versionList()"
            [(ngModel)]="from"
            (ngModelChange)="reloadDiff()"
            ariaLabel="from"
          />
          <span>→</span>
          <p-select
            [options]="versionList()"
            [(ngModel)]="to"
            (ngModelChange)="reloadDiff()"
            ariaLabel="to"
          />
        </div>
        @if (store.diff(); as d) {
          <div class="structured">
            @for (op of d.structured; track op.path) {
              <div>
                <span class="op">{{ op.path }}</span
                >: {{ op.before ?? '∅' }} → {{ op.after ?? '∅' }}
              </div>
            } @empty {
              <div>No structural differences.</div>
            }
          </div>
          <div class="diffwrap">
            <ay-monaco-diff [original]="d.yamlFrom" [modified]="d.yamlTo" />
          </div>
        }
      </section>
    </div>

    <p-dialog
      header="Publish version"
      [(visible)]="publishVisible"
      [modal]="true"
      [style]="{ width: '40rem' }"
    >
      <p>
        This hot-swaps the live signal engine to <strong>v{{ draftVersion() }}</strong
        >.
      </p>
      <input
        pInputText
        [(ngModel)]="notes"
        placeholder="Publish notes"
        style="width:100%"
        ariaLabel="Publish notes"
      />
      <ay-stress-advisory [strategyId]="id()" [version]="draftVersion()" />
      <ng-template #footer>
        <p-button label="Cancel" [text]="true" (onClick)="publishOpen.set(false)" />
        <p-button label="Publish" icon="pi pi-check" (onClick)="doPublish()" />
      </ng-template>
    </p-dialog>
  `,
  providers: [MessageService],
})
export class VersionsPage {
  protected readonly store = inject(StrategiesStore);
  private readonly route = inject(ActivatedRoute);
  private readonly toast = inject(MessageService);

  protected readonly id = signal<string>('');
  protected from = '';
  protected to = '';
  protected notes = '';
  protected readonly publishOpen = signal(false);

  protected readonly versionList = computed(() => this.store.versions().map((v) => v.version));
  protected readonly draftVersion = computed(
    () => this.store.versions().find((v) => v.status === 'draft')?.version ?? null,
  );

  protected get publishVisible(): boolean {
    return this.publishOpen();
  }
  protected set publishVisible(v: boolean) {
    this.publishOpen.set(v);
  }

  constructor() {
    this.route.paramMap.subscribe((p) => {
      const id = p.get('id') ?? '';
      this.id.set(id);
      this.store.loadDetail(id);
      this.store.loadVersions(id);
      // default compare = the two latest versions, once they arrive
      queueMicrotask(() => {
        const vs = this.store.versions();
        if (vs.length >= 1 && !this.to) {
          this.to = vs[0].version;
          this.from = (vs[1] ?? vs[0]).version;
          this.reloadDiff();
        }
      });
    });
  }

  protected sev(status: string): 'success' | 'warn' | 'secondary' {
    return status === 'published' ? 'success' : status === 'archived' ? 'secondary' : 'warn';
  }

  protected setFrom(version: string): void {
    this.from = version;
    this.reloadDiff();
  }

  protected reloadDiff(): void {
    if (this.id() && this.from && this.to && this.from !== this.to) {
      this.store.loadDiff(this.id(), this.from, this.to);
    }
  }

  protected openPublish(): void {
    this.publishOpen.set(true);
  }

  protected doPublish(): void {
    const target = this.draftVersion();
    this.store.publish(
      this.id(),
      { targetVersion: target ?? undefined, notes: this.notes },
      (v) => {
        this.publishOpen.set(false);
        this.toast.add({ severity: 'success', summary: 'Published', detail: `v${v} is live` });
      },
    );
  }

  protected rollback(version: string): void {
    if (confirm(`Roll back to v${version}? This creates a copy-forward draft.`)) {
      this.store.rollback(this.id(), { version }, (nv) =>
        this.toast.add({ severity: 'success', summary: 'Rolled back', detail: `new draft v${nv}` }),
      );
    }
  }
}
