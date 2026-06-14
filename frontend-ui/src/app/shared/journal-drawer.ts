import { ChangeDetectionStrategy, Component, inject, input, model, signal } from '@angular/core';
import { FormsModule } from '@angular/forms';
import { ButtonModule } from 'primeng/button';
import { DialogModule } from 'primeng/dialog';
import { InputTextModule } from 'primeng/inputtext';
import { JournalStore, type JournalLink } from '../stores/journal.store';

/**
 * JournalDrawer (F-44A): a dialog for journaling a signal / paper position / backtest trade (or a
 * free entry). Opened from those surfaces with the link prefilled; note + tags + discipline/emotion
 * ratings round-trip through the JournalStore. Screenshots are out of v1.
 */
@Component({
  selector: 'ay-journal-drawer',
  changeDetection: ChangeDetectionStrategy.OnPush,
  imports: [FormsModule, DialogModule, ButtonModule, InputTextModule],
  template: `
    <p-dialog
      header="Journal entry"
      [(visible)]="open"
      [modal]="true"
      [style]="{ width: '28rem' }"
      [dismissableMask]="true"
    >
      <div class="field">
        <label for="jnote">Note</label>
        <textarea id="jnote" rows="4" [(ngModel)]="note" style="width: 100%"></textarea>
      </div>
      <div class="field">
        <label for="jtags">Tags (comma-separated)</label>
        <input id="jtags" pInputText [(ngModel)]="tagsText" style="width: 100%" />
      </div>
      <div class="ratings">
        <label>Discipline <input type="number" min="1" max="5" [(ngModel)]="discipline" /></label>
        <label>Emotion <input type="number" min="1" max="5" [(ngModel)]="emotion" /></label>
      </div>
      <ng-template #footer>
        <p-button label="Cancel" [text]="true" (onClick)="open.set(false)" />
        <p-button label="Save" icon="pi pi-check" (onClick)="save()" />
      </ng-template>
    </p-dialog>
  `,
  styles: `
    .field {
      margin-bottom: 0.7rem;
    }
    .field label {
      display: block;
      font-size: 0.8rem;
      color: var(--ay-text-muted);
      margin-bottom: 0.2rem;
    }
    .ratings {
      display: flex;
      gap: 1rem;
      font-size: 0.85rem;
    }
    .ratings input {
      width: 3.5rem;
      margin-left: 0.3rem;
    }
  `,
})
export class JournalDrawer {
  readonly open = model(false);
  readonly link = input<JournalLink>({});

  private readonly store = inject(JournalStore);

  protected readonly note = signal('');
  protected readonly tagsText = signal('');
  protected readonly discipline = signal<number | null>(null);
  protected readonly emotion = signal<number | null>(null);

  protected save(): void {
    const tags = this.tagsText()
      .split(',')
      .map((t) => t.trim())
      .filter((t) => t.length > 0);
    this.store.create({
      ...this.link(),
      note: this.note(),
      tags,
      disciplineRating: this.discipline(),
      emotionRating: this.emotion(),
    });
    this.note.set('');
    this.tagsText.set('');
    this.discipline.set(null);
    this.emotion.set(null);
    this.open.set(false);
  }
}
