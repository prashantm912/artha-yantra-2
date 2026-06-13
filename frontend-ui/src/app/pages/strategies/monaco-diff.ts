import {
  ChangeDetectionStrategy,
  Component,
  DestroyRef,
  ElementRef,
  afterNextRender,
  effect,
  inject,
  input,
  viewChild,
} from '@angular/core';
import { ensureMonacoYaml, monaco } from './monaco-bootstrap';

/**
 * Monaco side-by-side YAML diff (E-11 screen 6): the version diff viewer. Monaco used directly
 * (no ngx wrapper); lazy with the versions route, so it shares the editor-route Monaco chunk.
 */
@Component({
  selector: 'ay-monaco-diff',
  changeDetection: ChangeDetectionStrategy.OnPush,
  styles: `
    .diff {
      width: 100%;
      height: 100%;
      min-height: 22rem;
      border: 1px solid var(--ay-border);
      border-radius: 8px;
      overflow: hidden;
    }
  `,
  template: `<div #host class="diff"></div>`,
})
export class MonacoDiff {
  readonly original = input<string>('');
  readonly modified = input<string>('');

  private readonly host = viewChild.required<ElementRef<HTMLDivElement>>('host');
  private diffEditor?: monaco.editor.IStandaloneDiffEditor;
  private originalModel?: monaco.editor.ITextModel;
  private modifiedModel?: monaco.editor.ITextModel;

  constructor() {
    afterNextRender(() => this.init());
    effect(() => {
      const orig = this.original();
      const mod = this.modified();
      this.originalModel?.setValue(orig);
      this.modifiedModel?.setValue(mod);
    });
    inject(DestroyRef).onDestroy(() => {
      this.originalModel?.dispose();
      this.modifiedModel?.dispose();
      this.diffEditor?.dispose();
    });
  }

  private init(): void {
    ensureMonacoYaml();
    try {
      const light = document.documentElement.classList.contains('ay-light');
      this.originalModel = monaco.editor.createModel(this.original(), 'yaml');
      this.modifiedModel = monaco.editor.createModel(this.modified(), 'yaml');
      this.diffEditor = monaco.editor.createDiffEditor(this.host().nativeElement, {
        readOnly: true,
        automaticLayout: true,
        renderSideBySide: true,
        minimap: { enabled: false },
        fontSize: 13,
        theme: light ? 'vs' : 'vs-dark',
        scrollBeyondLastLine: false,
      });
      this.diffEditor.setModel({ original: this.originalModel, modified: this.modifiedModel });
    } catch {
      // no real canvas (jsdom) — renders nothing
    }
  }
}
