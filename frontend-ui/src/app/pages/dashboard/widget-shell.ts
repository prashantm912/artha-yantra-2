import { ChangeDetectionStrategy, Component, computed, inject, input } from '@angular/core';
import { ButtonModule } from 'primeng/button';
import { MarketStore } from '../../stores/market.store';

/**
 * The `AyWidget` shell (E-2): title + collapse, content projected. Collapse state lives in the
 * MarketStore dashboard layout (localStorage-persisted) so it survives reloads — the v1 layout
 * contract before any drag-grid lands.
 */
@Component({
  selector: 'ay-widget',
  changeDetection: ChangeDetectionStrategy.OnPush,
  imports: [ButtonModule],
  styles: `
    .widget {
      display: flex;
      flex-direction: column;
      border: 1px solid var(--ay-border);
      border-radius: 10px;
      background: var(--ay-surface-1);
      overflow: hidden;
      height: 100%;
    }
    header {
      display: flex;
      align-items: center;
      gap: 0.5rem;
      padding: 0.5rem 0.4rem 0.5rem 0.85rem;
      border-bottom: 1px solid var(--ay-border);
    }
    h2 {
      font-size: 0.85rem;
      font-weight: 600;
      letter-spacing: 0.3px;
      color: var(--ay-text-muted);
      text-transform: uppercase;
      margin: 0;
      flex: 1;
    }
    .body {
      padding: 0.75rem 0.85rem;
      overflow: auto;
      flex: 1;
      min-height: 0;
    }
  `,
  template: `
    <section class="widget">
      <header>
        <h2>{{ title() }}</h2>
        <p-button
          [text]="true"
          size="small"
          [icon]="collapsed() ? 'pi pi-chevron-down' : 'pi pi-chevron-up'"
          [ariaLabel]="(collapsed() ? 'Expand ' : 'Collapse ') + title()"
          (onClick)="toggle()"
        />
      </header>
      @if (!collapsed()) {
        <div class="body"><ng-content /></div>
      }
    </section>
  `,
})
export class WidgetShell {
  readonly title = input.required<string>();
  readonly widgetId = input.required<string>();

  private readonly market = inject(MarketStore);
  protected readonly collapsed = computed(
    () => this.market.dashboardLayout()[this.widgetId()] ?? false,
  );

  protected toggle(): void {
    this.market.setWidgetCollapsed(this.widgetId(), !this.collapsed());
  }
}
