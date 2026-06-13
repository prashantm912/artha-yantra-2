import { ApplicationConfig, provideBrowserGlobalErrorListeners } from '@angular/core';
import { provideHttpClient, withInterceptors } from '@angular/common/http';
import { provideAnimationsAsync } from '@angular/platform-browser/animations/async';
import { provideRouter } from '@angular/router';
import { providePrimeNG } from 'primeng/config';
import { MessageService } from 'primeng/api';
import Aura from '@primeuix/themes/aura';

import { routes } from './app.routes';
import { errorEnvelopeInterceptor } from './core/error.interceptor';

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
        preset: Aura,
        options: {
          // dark default; SessionStore toggles .ay-light on <html> (C-2.23)
          darkModeSelector: ':root:not(.ay-light)',
        },
      },
    }),
    MessageService,
  ],
};
