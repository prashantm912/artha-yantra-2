import { ApplicationConfig, provideBrowserGlobalErrorListeners } from '@angular/core';
import { provideHttpClient, withInterceptors } from '@angular/common/http';
import { provideAnimationsAsync } from '@angular/platform-browser/animations/async';
import { provideRouter } from '@angular/router';
import { providePrimeNG } from 'primeng/config';
import { MessageService } from 'primeng/api';
import Aura from '@primeuix/themes/aura';
import { definePreset } from '@primeuix/themes';

import { routes } from './app.routes';
import { errorEnvelopeInterceptor } from './core/error.interceptor';

// Aura's default emerald primary (.500 = #10b981) fails the WCAG 1.4.3 4.5:1 floor as
// outlined/text-button text on white; darken the LIGHT-scheme primary so it passes (dark
// scheme keeps the lighter shade — it's on dark surfaces). E-7.
const AyAura = definePreset(Aura, {
  semantic: {
    colorScheme: {
      light: {
        primary: {
          color: '{primary.700}',
          contrastColor: '#ffffff',
          hoverColor: '{primary.800}',
          activeColor: '{primary.800}',
        },
      },
    },
  },
});

// Zoneless by design (D1): Angular 21 defaults to zoneless change detection -
// every template read is a signal, no Zone.js anywhere in the bundle.
export const appConfig: ApplicationConfig = {
  providers: [
    provideBrowserGlobalErrorListeners(),
    provideRouter(routes),
    provideHttpClient(withInterceptors([errorEnvelopeInterceptor])),
    provideAnimationsAsync(),
    providePrimeNG({
      theme: {
        preset: AyAura,
        options: {
          // dark default; SessionStore toggles .ay-dark / .ay-light on <html> (C-2.23).
          // Use a plain class selector — a `:root`-anchored selector (e.g. `:root:not(.ay-light)`)
          // collides with @primeuix's own `:root, :host` colour-scheme wrapper and emits a dead
          // `& :root, & :host` rule, leaving every PrimeNG component on the LIGHT scheme.
          darkModeSelector: '.ay-dark',
        },
      },
    }),
    MessageService,
  ],
};
