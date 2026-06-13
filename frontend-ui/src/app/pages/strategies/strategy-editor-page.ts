import {
  ChangeDetectionStrategy,
  Component,
  DestroyRef,
  computed,
  inject,
  signal,
} from '@angular/core';
import { FormsModule } from '@angular/forms';
import { ActivatedRoute, Router } from '@angular/router';
import { ButtonModule } from 'primeng/button';
import { CheckboxModule } from 'primeng/checkbox';
import { InputNumberModule } from 'primeng/inputnumber';
import { InputTextModule } from 'primeng/inputtext';
import { MessageModule } from 'primeng/message';
import { SelectModule } from 'primeng/select';
import { TagModule } from 'primeng/tag';
import { StrategiesStore, type VersionBump } from '../../stores/strategies.store';
import { MonacoYamlEditor } from './monaco-yaml-editor';
import { QuickBacktestDrawer } from './quick-backtest-drawer';
import { type StrategyDoc, formIndicators, parseDoc, withField } from './strategy-form';

/**
 * /strategies/:id/edit (Phase 36, E-11 screen 2): dual-mode editor — a CD-11 form pane (metadata +
 * indicator weight/optional toggles ONLY in v1) two-way synced with the Monaco YAML pane bound to
 * `strategy-schema/v1`. Debounced server `POST /validate` (authoritative) surfaces semantic errors;
 * save auto-bumps the version; a dirty-state guard blocks navigation loss. `id === 'new'` creates.
 *
 * v1 form scope (CD-11): universe pickers / rule builder / risk fields stay YAML-only (parked in
 * PHASE_GATES at Phase 36). Form edits re-stringify the doc (comments not preserved on form edits;
 * pure-YAML editing in Monaco preserves them).
 */
@Component({
  selector: 'ay-strategy-editor-page',
  changeDetection: ChangeDetectionStrategy.OnPush,
  imports: [
    FormsModule,
    ButtonModule,
    SelectModule,
    InputTextModule,
    InputNumberModule,
    CheckboxModule,
    TagModule,
    MessageModule,
    MonacoYamlEditor,
    QuickBacktestDrawer,
  ],
  styles: `
    .toolbar {
      display: flex;
      gap: 0.6rem;
      align-items: center;
      margin-bottom: 0.8rem;
      flex-wrap: wrap;
    }
    .spacer {
      flex: 1;
    }
    .panes {
      display: grid;
      grid-template-columns: minmax(16rem, 22rem) minmax(0, 1fr);
      gap: 1rem;
      height: calc(100vh - 16rem);
    }
    .form {
      overflow: auto;
      border: 1px solid var(--ay-border);
      border-radius: 8px;
      background: var(--ay-surface-1);
      padding: 0.9rem;
    }
    .form h3 {
      font-size: 0.85rem;
      margin: 0.2rem 0 0.6rem;
      color: var(--ay-text-muted);
    }
    .field {
      margin-bottom: 0.7rem;
    }
    .field .fl {
      display: block;
      font-size: 0.8rem;
      color: var(--ay-text-muted);
      margin-bottom: 0.2rem;
    }
    .ind {
      display: grid;
      grid-template-columns: 1fr auto auto;
      gap: 0.4rem;
      align-items: center;
      margin-bottom: 0.5rem;
    }
    .ind .alias {
      font-size: 0.85rem;
    }
    .validation {
      margin-top: 0.8rem;
      max-height: 9rem;
      overflow: auto;
    }
    .issue {
      font-size: 0.82rem;
      margin: 0.15rem 0;
    }
    .issue .path {
      color: var(--ay-text-muted);
      font-family: monospace;
    }
    .invalid {
      color: var(--ay-bear);
    }
  `,
  template: `
    <h1 class="ay-sr-only">Strategy editor</h1>
    <div class="toolbar">
      <strong>{{ store.current()?.name ?? 'New strategy' }}</strong>
      @if (store.current(); as c) {
        <p-tag [value]="'v' + c.version" severity="info" />
        <p-tag [value]="c.status" [severity]="c.status === 'published' ? 'success' : 'warn'" />
      }
      @if (validBadge() === 'valid') {
        <p-tag value="valid" severity="success" />
      } @else if (validBadge() === 'invalid') {
        <p-tag value="invalid" severity="danger" />
      }
      <span class="spacer"></span>
      @if (!isNew()) {
        <p-select [options]="bumps" [(ngModel)]="bump" />
      }
      <p-button
        [label]="isNew() ? 'Create draft' : 'Save draft'"
        icon="pi pi-save"
        [loading]="store.saving()"
        [disabled]="saveDisabled()"
        (onClick)="save()"
      />
      <p-button
        label="Quick backtest"
        icon="pi pi-play"
        [outlined]="true"
        [disabled]="isNew()"
        (onClick)="drawerOpen.set(true)"
      />
    </div>

    <div class="panes">
      <aside class="form">
        <h3>Metadata</h3>
        @if (doc(); as d) {
          <div class="field">
            <span class="fl">Name</span>
            <input
              pInputText
              [ngModel]="d.name ?? ''"
              (ngModelChange)="setName($event)"
              ariaLabel="Strategy name"
            />
          </div>
          <h3>Indicators (weight / optional)</h3>
          @for (ind of indicators(); track ind.i) {
            <div class="ind">
              <span class="alias"
                >{{ ind.alias }} <small>({{ ind.name }})</small></span
              >
              <p-inputnumber
                [ngModel]="ind.weight"
                (ngModelChange)="setWeight(ind.i, $event)"
                [min]="0"
                [maxFractionDigits]="2"
                [showButtons]="false"
                inputStyleClass="w-5rem"
              />
              <p-checkbox
                [ngModel]="ind.optional"
                (ngModelChange)="setOptional(ind.i, $event)"
                [binary]="true"
                ariaLabel="optional"
              />
            </div>
          }
        } @else {
          <p-message
            severity="warn"
            text="YAML can't be parsed — edit it in the pane to the right."
          />
        }
      </aside>

      <section>
        <ay-monaco-yaml-editor
          [value]="store.draft()"
          (valueChange)="onYaml($event)"
          [schema]="store.schema()"
        />
        <div class="validation">
          @if (store.validation(); as v) {
            @if (v.valid) {
              <p-message severity="success" text="Schema + semantic validation passed." />
            }
            @for (e of v.errors; track e.path + e.message) {
              <div class="issue invalid">
                ✗ <span class="path">{{ e.path }}</span> — {{ e.message }}
              </div>
            }
            @for (w of v.warnings; track w.path + w.message) {
              <div class="issue">
                <span class="path">{{ w.path }}</span> — {{ w.message }}
              </div>
            }
          }
        </div>
      </section>
    </div>

    <ay-quick-backtest-drawer
      [(open)]="drawerOpen"
      [strategyId]="savedId()"
      [version]="store.current()?.version ?? null"
    />
  `,
})
export class StrategyEditorPage {
  protected readonly store = inject(StrategiesStore);
  private readonly route = inject(ActivatedRoute);
  private readonly router = inject(Router);

  protected readonly drawerOpen = signal(false);
  protected readonly bumps: VersionBump[] = ['patch', 'minor', 'major'];
  protected bump: VersionBump = 'minor';

  private readonly routeId = signal<string>('new');
  protected readonly isNew = computed(() => this.routeId() === 'new');
  protected readonly savedId = computed(() => (this.isNew() ? null : this.routeId()));

  /** Parsed draft for the form pane (null when the YAML can't be parsed). */
  protected readonly doc = computed<StrategyDoc | null>(() => parseDoc(this.store.draft()));

  protected readonly indicators = computed(() => formIndicators(this.doc()));

  protected readonly validBadge = computed<'valid' | 'invalid' | null>(() => {
    const v = this.store.validation();
    return v ? (v.valid ? 'valid' : 'invalid') : null;
  });

  private validateTimer?: ReturnType<typeof setTimeout>;

  constructor() {
    this.store.loadSchema();
    this.route.paramMap.subscribe((params) => {
      const id = params.get('id') ?? 'new';
      this.routeId.set(id);
      if (id === 'new') {
        if (!this.store.draft().trim()) {
          this.store.startNewDraft(
            'schema: strategy-schema/v1\nid: my-strategy\nname: "My Strategy"\nversion: 1.0.0\n',
          );
        }
        this.scheduleValidate();
      } else {
        this.store.loadDetail(id);
        this.scheduleValidate();
      }
    });
    inject(DestroyRef).onDestroy(() => clearTimeout(this.validateTimer));
  }

  protected saveDisabled(): boolean {
    return this.doc() === null || this.store.validation()?.valid === false;
  }

  protected onYaml(yaml: string): void {
    this.store.setDraft(yaml);
    this.scheduleValidate();
  }

  private scheduleValidate(): void {
    clearTimeout(this.validateTimer);
    this.validateTimer = setTimeout(() => this.store.validate(), 500);
  }

  // --- form → YAML write-back (re-stringify the whole doc; comments not preserved on form edits) ---
  private patchDoc(mutate: (doc: StrategyDoc) => void): void {
    this.store.setDraft(withField(this.store.draft(), mutate));
    this.scheduleValidate();
  }

  protected setName(name: string): void {
    this.patchDoc((d) => (d.name = name));
  }

  protected setWeight(i: number, weight: number): void {
    this.patchDoc((d) => {
      if (d.indicators?.[i]) {
        d.indicators[i]['weight'] = weight;
      }
    });
  }

  protected setOptional(i: number, optional: boolean): void {
    this.patchDoc((d) => {
      const ind = d.indicators?.[i];
      if (!ind) {
        return;
      }
      if (optional) {
        ind['optional'] = true;
      } else {
        delete ind['optional'];
      }
    });
  }

  protected save(): void {
    if (this.isNew()) {
      const d = this.doc();
      const name = (d?.name as string) ?? 'New strategy';
      this.store.create({ name, config: this.store.draft() }, (id) =>
        this.router.navigate(['/strategies', id, 'edit']),
      );
    } else {
      this.store.saveDraft(this.routeId(), this.bump);
    }
  }

  /** Dirty-state navigation guard hook. */
  canDeactivate(): boolean {
    return !this.store.dirty() || confirm('You have unsaved changes. Leave without saving?');
  }
}

/** Route `canDeactivate` guard: confirm before discarding an unsaved draft. */
export function unsavedStrategyGuard(component: StrategyEditorPage): boolean {
  return component.canDeactivate();
}
