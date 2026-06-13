import { ChangeDetectionStrategy, Component, inject, signal } from '@angular/core';
import { FormsModule } from '@angular/forms';
import { ActivatedRoute } from '@angular/router';
import { ButtonModule } from 'primeng/button';
import { PasswordModule } from 'primeng/password';
import { MessageModule } from 'primeng/message';
import { SessionStore } from '../../core/session.store';

/** Single-owner gateway form login (C-2.24). */
@Component({
  selector: 'ay-login-page',
  changeDetection: ChangeDetectionStrategy.OnPush,
  imports: [FormsModule, ButtonModule, PasswordModule, MessageModule],
  styles: `
    .login-wrap {
      min-height: 100vh;
      display: grid;
      place-items: center;
      background: var(--ay-surface-0);
    }
    .login-card {
      width: 22rem;
      padding: 2rem;
      border: 1px solid var(--ay-border);
      border-radius: 12px;
      background: var(--ay-surface-1);
      display: flex;
      flex-direction: column;
      gap: 1rem;
    }
    h1 {
      margin: 0;
      font-size: 1.3rem;
      color: var(--ay-text);
    }
    .tagline {
      color: var(--ay-text-muted);
      font-size: 0.85rem;
      margin: 0;
    }
  `,
  template: `
    <div class="login-wrap">
      <form class="login-card" (ngSubmit)="submit()">
        <h1>ArthaYantra</h1>
        <p class="tagline">Owner sign-in</p>
        <p-password
          [(ngModel)]="password"
          name="password"
          [feedback]="false"
          [toggleMask]="true"
          placeholder="Owner password"
          inputStyleClass="w-full"
          autocomplete="current-password"
        />
        @if (session.loginError(); as error) {
          <p-message severity="error" [text]="error" />
        }
        <p-button
          type="submit"
          label="Sign in"
          [loading]="session.loggingIn()"
          [disabled]="!password()"
        />
      </form>
    </div>
  `,
})
export class LoginPage {
  protected readonly session = inject(SessionStore);
  private readonly route = inject(ActivatedRoute);
  protected readonly password = signal('');

  protected submit(): void {
    const returnUrl = this.route.snapshot.queryParamMap.get('returnUrl') ?? '/';
    this.session.login(this.password(), returnUrl);
  }
}
