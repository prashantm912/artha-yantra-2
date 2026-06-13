import { ChangeDetectionStrategy, Component } from '@angular/core';

/** Phase-25 placeholder home — the /signals MVP page replaces top billing in Phase 26. */
@Component({
  selector: 'ay-home-page',
  changeDetection: ChangeDetectionStrategy.OnPush,
  styles: `
    .empty {
      display: grid;
      place-items: center;
      height: 100%;
      color: var(--ay-text-muted);
      text-align: center;
    }
    h2 {
      color: var(--ay-text);
      margin-bottom: 0.4rem;
    }
  `,
  template: `
    <div class="empty">
      <div>
        <h2>Shell is up</h2>
        <p>Login, session, theming and routing are live. The signals page lands in Phase 26.</p>
      </div>
    </div>
  `,
})
export class HomePage {}
