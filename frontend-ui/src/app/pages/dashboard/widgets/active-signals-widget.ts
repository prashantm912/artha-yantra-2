import { ChangeDetectionStrategy, Component, computed, inject } from '@angular/core';
import { Router } from '@angular/router';
import { TagModule } from 'primeng/tag';
import { formatDecimal } from '../../../core/decimal';
import { SignalsStore } from '../../../stores/signals.store';

/**
 * ActiveSignals widget (E-2): the newest live signals; click-through to the full /signals page
 * with the reasoning breakdown. Reads the existing SignalsStore ring buffer — no extra feed.
 */
@Component({
  selector: 'ay-active-signals-widget',
  changeDetection: ChangeDetectionStrategy.OnPush,
  imports: [TagModule],
  styles: `
    ul {
      list-style: none;
      margin: 0;
      padding: 0;
      display: flex;
      flex-direction: column;
      gap: 0.35rem;
    }
    li {
      display: grid;
      grid-template-columns: 3.4rem 1fr auto auto;
      gap: 0.5rem;
      align-items: center;
      padding: 0.35rem 0.45rem;
      border-radius: 6px;
      cursor: pointer;
      font-size: 0.85rem;
    }
    li:hover {
      background: var(--ay-surface-2);
    }
    .time {
      color: var(--ay-text-muted);
      font-variant-numeric: tabular-nums;
    }
    .score {
      font-variant-numeric: tabular-nums;
    }
    .empty {
      color: var(--ay-text-muted);
      font-size: 0.85rem;
    }
  `,
  template: `
    @if (recent().length) {
      <ul>
        @for (s of recent(); track s.id) {
          <li (click)="open(s.id)" (keyup.enter)="open(s.id)" tabindex="0">
            <span class="time">{{ s.generatedAt.slice(11, 16) }}</span>
            <span>{{ s.tradingsymbol }}</span>
            <p-tag
              [severity]="s.signalType === 'ENTRY' ? 'success' : 'warn'"
              [value]="s.signalType + ' ' + s.side"
            />
            <span class="score">{{ score(s.compositeScore) }}</span>
          </li>
        }
      </ul>
    } @else {
      <p class="empty">No live signals yet — publish a strategy to start emitting signals.</p>
    }
  `,
})
export class ActiveSignalsWidget {
  private readonly store = inject(SignalsStore);
  private readonly router = inject(Router);

  protected readonly recent = computed(() =>
    this.store
      .items()
      .filter((s) => s.status === 'ACTIVE')
      .slice(0, 7),
  );

  protected score(value: string): string {
    return formatDecimal(value, 4);
  }

  protected open(id: number): void {
    this.store.select(id);
    void this.router.navigate(['/signals']);
  }
}
