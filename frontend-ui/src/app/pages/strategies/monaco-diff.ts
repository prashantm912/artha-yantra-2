import { ChangeDetectionStrategy, Component, computed, input } from '@angular/core';

interface DiffRow {
  left: string | null;
  right: string | null;
  kind: 'same' | 'add' | 'del';
}

/**
 * Read-only side-by-side YAML diff — a plain LCS line-diff, no Monaco. The Monaco diff editor was
 * removed for the same reason as the YAML editor (`monaco-yaml-editor`): the Monaco worker fails to
 * register under the zoneless prod build, so the diff panes rendered blank. Keeps the same selector
 * + `original`/`modified` inputs, so the compare + versions pages are no-touch drop-ins.
 */
@Component({
  selector: 'ay-monaco-diff',
  changeDetection: ChangeDetectionStrategy.OnPush,
  styles: `
    .diff {
      width: 100%;
      min-height: 22rem;
      max-height: 60vh;
      overflow: auto;
      border: 1px solid var(--ay-border);
      border-radius: 8px;
      font-family: 'Cascadia Code', 'Consolas', monospace;
      font-size: 12.5px;
      line-height: 1.5;
      background: var(--ay-surface-1);
    }
    .r {
      display: grid;
      grid-template-columns: 1fr 1fr;
    }
    .c {
      padding: 0 0.6rem;
      white-space: pre-wrap;
      word-break: break-word;
      border-right: 1px solid var(--ay-border);
      min-height: 1.5em;
    }
    .c:last-child {
      border-right: none;
    }
    .del .c {
      background: color-mix(in srgb, var(--ay-bear, #ef4444) 16%, transparent);
    }
    .add .c {
      background: color-mix(in srgb, var(--ay-success, #16a34a) 16%, transparent);
    }
  `,
  template: `
    <div class="diff" role="region" aria-label="YAML diff: original versus modified">
      @for (row of rows(); track $index) {
        <div class="r" [class.add]="row.kind === 'add'" [class.del]="row.kind === 'del'">
          <span class="c">{{ row.left ?? '' }}</span>
          <span class="c">{{ row.right ?? '' }}</span>
        </div>
      }
    </div>
  `,
})
export class MonacoDiff {
  readonly original = input<string>('');
  readonly modified = input<string>('');

  protected readonly rows = computed<DiffRow[]>(() =>
    lineDiff((this.original() ?? '').split('\n'), (this.modified() ?? '').split('\n')),
  );
}

/** Minimal LCS line-diff → aligned side-by-side rows (O(n·m); YAML configs are small). */
function lineDiff(a: string[], b: string[]): DiffRow[] {
  const n = a.length;
  const m = b.length;
  const lcs: number[][] = Array.from({ length: n + 1 }, () => new Array<number>(m + 1).fill(0));
  for (let i = n - 1; i >= 0; i--) {
    for (let j = m - 1; j >= 0; j--) {
      lcs[i][j] = a[i] === b[j] ? lcs[i + 1][j + 1] + 1 : Math.max(lcs[i + 1][j], lcs[i][j + 1]);
    }
  }
  const rows: DiffRow[] = [];
  let i = 0;
  let j = 0;
  while (i < n && j < m) {
    if (a[i] === b[j]) {
      rows.push({ left: a[i], right: b[j], kind: 'same' });
      i++;
      j++;
    } else if (lcs[i + 1][j] >= lcs[i][j + 1]) {
      rows.push({ left: a[i], right: null, kind: 'del' });
      i++;
    } else {
      rows.push({ left: null, right: b[j], kind: 'add' });
      j++;
    }
  }
  while (i < n) {
    rows.push({ left: a[i++], right: null, kind: 'del' });
  }
  while (j < m) {
    rows.push({ left: null, right: b[j++], kind: 'add' });
  }
  return rows;
}
