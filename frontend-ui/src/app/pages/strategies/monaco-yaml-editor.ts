import { ChangeDetectionStrategy, Component, input, model } from '@angular/core';
import { FormsModule } from '@angular/forms';

/**
 * YAML editor — plain `<textarea>` fallback (interim). Monaco was removed from this surface: under
 * the zoneless production build the monaco-yaml worker fails to register at runtime ("Missing
 * requestHandler doValidation") and floods a ResizeObserver loop, which broke the whole editor
 * route. The authoritative server `POST /validate` still surfaces schema + semantic errors in the
 * page's validation panel — only inline squiggles / autocomplete are lost until Monaco is restored.
 * `schema` is accepted for API compatibility (unused here). Same selector + two-way `value`, so the
 * editor page is a no-touch drop-in.
 */
@Component({
  selector: 'ay-monaco-yaml-editor',
  changeDetection: ChangeDetectionStrategy.OnPush,
  imports: [FormsModule],
  styles: `
    textarea {
      width: 100%;
      height: 100%;
      min-height: 24rem;
      box-sizing: border-box;
      border: 1px solid var(--ay-border);
      border-radius: 8px;
      background: var(--ay-surface-1);
      color: inherit;
      font-family: 'Cascadia Code', 'Fira Code', 'Consolas', monospace;
      font-size: 13px;
      line-height: 1.5;
      padding: 0.6rem 0.8rem;
      resize: vertical;
      tab-size: 2;
      white-space: pre;
      overflow: auto;
    }
  `,
  template: `
    <textarea
      [ngModel]="value()"
      (ngModelChange)="value.set($event)"
      spellcheck="false"
      autocomplete="off"
      autocapitalize="off"
      wrap="off"
      aria-label="Strategy YAML"
    ></textarea>
  `,
})
export class MonacoYamlEditor {
  /** Two-way YAML buffer. */
  readonly value = model<string>('');
  /** Accepted for API compatibility; inline schema validation is server-side (`POST /validate`) now. */
  readonly schema = input<object | null>(null);
}
