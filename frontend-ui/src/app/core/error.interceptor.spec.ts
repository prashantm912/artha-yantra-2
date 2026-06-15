import { TestBed } from '@angular/core/testing';
import { HttpClient, HttpContext, provideHttpClient, withInterceptors } from '@angular/common/http';
import { HttpTestingController, provideHttpClientTesting } from '@angular/common/http/testing';
import { MessageService } from 'primeng/api';
import { describe, expect, it, beforeEach, vi } from 'vitest';
import { SILENCE_ERROR_TOAST, errorEnvelopeInterceptor } from './error.interceptor';

describe('errorEnvelopeInterceptor', () => {
  let http: HttpClient;
  let backend: HttpTestingController;
  let toast: MessageService;

  beforeEach(() => {
    TestBed.configureTestingModule({
      providers: [
        provideHttpClient(withInterceptors([errorEnvelopeInterceptor])),
        provideHttpClientTesting(),
        MessageService,
      ],
    });
    http = TestBed.inject(HttpClient);
    backend = TestBed.inject(HttpTestingController);
    toast = TestBed.inject(MessageService);
  });

  it('maps the D8 envelope to one toast', () => {
    const add = vi.spyOn(toast, 'add');
    http.get('/api/v1/strategies').subscribe({ error: () => undefined });
    backend
      .expectOne('/api/v1/strategies')
      .flush(
        { code: 'STRATEGY_SCHEMA_INVALID', message: 'config fails strategy-schema/v1' },
        { status: 400, statusText: 'Bad Request' },
      );
    expect(add).toHaveBeenCalledWith(
      expect.objectContaining({
        severity: 'error',
        summary: 'STRATEGY_SCHEMA_INVALID',
        detail: 'config fails strategy-schema/v1',
      }),
    );
  });

  it('stays silent for requests carrying the SILENCE_ERROR_TOAST context', () => {
    const add = vi.spyOn(toast, 'add');
    http
      .post(
        '/api/v1/market/subscriptions',
        {},
        {
          context: new HttpContext().set(SILENCE_ERROR_TOAST, true),
        },
      )
      .subscribe({ error: () => undefined });
    backend
      .expectOne('/api/v1/market/subscriptions')
      .flush({ code: 'NOT_FOUND_INSTRUMENT' }, { status: 404, statusText: 'Not Found' });
    expect(add).not.toHaveBeenCalled();
  });

  it('keeps session-probe 401s silent', () => {
    const add = vi.spyOn(toast, 'add');
    http.get('/api/v1/auth/session').subscribe({ error: () => undefined });
    backend
      .expectOne('/api/v1/auth/session')
      .flush({ code: 'AUTH_REQUIRED' }, { status: 401, statusText: 'Unauthorized' });
    expect(add).not.toHaveBeenCalled();
  });
});
