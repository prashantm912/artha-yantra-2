import {
  ChangeDetectionStrategy,
  Component,
  DestroyRef,
  ElementRef,
  afterNextRender,
  effect,
  inject,
  input,
  model,
  viewChild,
} from '@angular/core';
import { ensureMonacoYaml, monaco } from './monaco-bootstrap';

/**
 * The YAML editor: Monaco bound to monaco-yaml against `strategy-schema/v1` (autocomplete, hover,
 * schema squiggles), used DIRECTLY (no ngx wrapper — D2). Two-way `value`; the live `schema` drives
 * monaco-yaml validation. Lazy with its route, so Monaco stays out of the initial bundle.
 */
@Component({
  selector: 'ay-monaco-yaml-editor',
  changeDetection: ChangeDetectionStrategy.OnPush,
  styles: `
    .editor {
      width: 100%;
      height: 100%;
      min-height: 24rem;
      border: 1px solid var(--ay-border);
      border-radius: 8px;
      overflow: hidden;
    }
  `,
  template: `<div #host class="editor"></div>`,
})
export class MonacoYamlEditor {
  /** Two-way YAML buffer. */
  readonly value = model<string>('');
  /** The JSON Schema object served by `GET /strategies/schema/v1`. */
  readonly schema = input<object | null>(null);

  private readonly host = viewChild.required<ElementRef<HTMLDivElement>>('host');
  private editor?: monaco.editor.IStandaloneCodeEditor;
  private applyingExternal = false;

  constructor() {
    afterNextRender(() => this.init());

    // external value → editor (guard against the change-content echo)
    effect(() => {
      const v = this.value();
      if (this.editor && this.editor.getValue() !== v) {
        this.applyingExternal = true;
        this.editor.setValue(v);
        this.applyingExternal = false;
      }
    });

    // live schema → monaco-yaml validation/autocomplete
    effect(() => {
      const s = this.schema();
      if (s) {
        void ensureMonacoYaml().update({
          enableSchemaRequest: false,
          validate: true,
          hover: true,
          completion: true,
          schemas: [{ uri: 'inmemory://schema/strategy-v1.json', fileMatch: ['*'], schema: s }],
        });
      }
    });

    inject(DestroyRef).onDestroy(() => {
      this.editor?.getModel()?.dispose();
      this.editor?.dispose();
    });
  }

  private init(): void {
    ensureMonacoYaml();
    const light = document.documentElement.classList.contains('ay-light');
    const model = monaco.editor.createModel(
      this.value(),
      'yaml',
      monaco.Uri.parse('inmemory://model/strategy-draft.yaml'),
    );
    this.editor = monaco.editor.create(this.host().nativeElement, {
      model,
      language: 'yaml',
      theme: light ? 'vs' : 'vs-dark',
      automaticLayout: true,
      minimap: { enabled: false },
      fontSize: 13,
      tabSize: 2,
      scrollBeyondLastLine: false,
      renderWhitespace: 'boundary',
    });
    this.editor.onDidChangeModelContent(() => {
      if (!this.applyingExternal) {
        this.value.set(this.editor!.getValue());
      }
    });
  }
}
