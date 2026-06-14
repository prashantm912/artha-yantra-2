import { ChangeDetectionStrategy, Component, OnInit, inject, signal } from '@angular/core';
import { FormsModule } from '@angular/forms';
import { ButtonModule } from 'primeng/button';
import { InputTextModule } from 'primeng/inputtext';
import { SelectModule } from 'primeng/select';
import { TableModule } from 'primeng/table';
import { TagModule } from 'primeng/tag';
import { JournalDrawer } from '../../shared/journal-drawer';
import { JournalStore, type JournalEntry } from '../../stores/journal.store';

/**
 * /journal (F-44A): the weekly-review surface — a filterable entry list (tag / linked-entity), each
 * row beside its linked-object summary. Free entries are journaled via the JournalDrawer here too.
 */
@Component({
  selector: 'ay-journal-page',
  changeDetection: ChangeDetectionStrategy.OnPush,
  imports: [
    FormsModule,
    TableModule,
    TagModule,
    ButtonModule,
    InputTextModule,
    SelectModule,
    JournalDrawer,
  ],
  styles: `
    .filters {
      display: flex;
      gap: 0.6rem;
      align-items: center;
      margin-bottom: 0.8rem;
    }
    .spacer {
      flex: 1;
    }
    .tags {
      display: flex;
      gap: 0.3rem;
      flex-wrap: wrap;
    }
    .linked {
      font-size: 0.8rem;
      color: var(--ay-text-muted);
    }
  `,
  template: `
    <h1 class="ay-sr-only">Trade journal</h1>
    <div class="filters">
      <input
        pInputText
        placeholder="Filter by tag"
        [ngModel]="store.tag()"
        (ngModelChange)="store.setTag($event || null)"
      />
      <p-select
        [options]="linkedOptions"
        optionLabel="label"
        optionValue="value"
        [ngModel]="store.linkedTo()"
        (ngModelChange)="store.setLinkedTo($event)"
        placeholder="Linked to"
        [showClear]="true"
      />
      <span class="spacer"></span>
      <p-button label="New entry" icon="pi pi-plus" (onClick)="drawerOpen.set(true)" />
    </div>

    <p-table [value]="store.items()" dataKey="id" [loading]="store.loading()">
      <ng-template #header>
        <tr>
          <th>Note</th>
          <th>Tags</th>
          <th>Linked</th>
          <th class="num">Disc.</th>
          <th class="num">Emo.</th>
          <th>Created</th>
          <th></th>
        </tr>
      </ng-template>
      <ng-template #body let-e>
        <tr>
          <td>{{ e.note }}</td>
          <td>
            <span class="tags">
              @for (t of e.tags; track t) {
                <p-tag [value]="t" severity="secondary" />
              }
            </span>
          </td>
          <td class="linked">{{ linkLabel(e) }}</td>
          <td class="num">{{ e.disciplineRating ?? '—' }}</td>
          <td class="num">{{ e.emotionRating ?? '—' }}</td>
          <td>{{ e.createdAt?.slice(0, 10) }}</td>
          <td>
            <p-button
              size="small"
              [text]="true"
              icon="pi pi-trash"
              severity="danger"
              ariaLabel="Delete"
              (onClick)="store.remove(e.id)"
            />
          </td>
        </tr>
      </ng-template>
      <ng-template #emptymessage>
        <tr>
          <td colspan="7">No journal entries — annotate a signal, paper trade or backtest.</td>
        </tr>
      </ng-template>
    </p-table>

    <ay-journal-drawer [(open)]="drawerOpen" />
  `,
})
export class JournalPage implements OnInit {
  protected readonly store = inject(JournalStore);
  protected readonly drawerOpen = signal(false);

  protected readonly linkedOptions = [
    { label: 'Signals', value: 'signal' },
    { label: 'Paper', value: 'paper' },
    { label: 'Backtests', value: 'backtest' },
    { label: 'Free entries', value: 'none' },
  ];

  ngOnInit(): void {
    this.store.load();
  }

  protected linkLabel(e: JournalEntry): string {
    if (e.signalId) {
      return `signal #${e.signalId}`;
    }
    if (e.paperPositionId) {
      return `paper #${e.paperPositionId}`;
    }
    if (e.backtestRunId || e.backtestTradeId) {
      return `backtest ${e.backtestRunId?.slice(0, 8) ?? ''}${e.backtestTradeId ? ' #' + e.backtestTradeId : ''}`;
    }
    return 'free';
  }
}
