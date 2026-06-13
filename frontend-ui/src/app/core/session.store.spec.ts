import { TestBed } from '@angular/core/testing';
import { provideHttpClient } from '@angular/common/http';
import { HttpTestingController, provideHttpClientTesting } from '@angular/common/http/testing';
import { provideRouter, Router } from '@angular/router';
import { describe, expect, it, beforeEach, afterEach, vi } from 'vitest';
import { SessionStore } from './session.store';

describe('SessionStore', () => {
  let http: HttpTestingController;
  let store: InstanceType<typeof SessionStore>;

  beforeEach(() => {
    localStorage.clear();
    TestBed.configureTestingModule({
      providers: [provideHttpClient(), provideHttpClientTesting(), provideRouter([])],
    });
    http = TestBed.inject(HttpTestingController);
    store = TestBed.inject(SessionStore);
    // the empty test router cannot resolve real URLs - navigation is not under test here
    vi.spyOn(TestBed.inject(Router), 'navigateByUrl').mockResolvedValue(true);
  });

  afterEach(() => http.verify());

  it('probe transitions unknown -> checking -> authenticated on 200', async () => {
    const probe = store.probe();
    expect(store.auth()).toBe('checking');
    http.expectOne('/api/v1/auth/session').flush({});
    await expect(probe).resolves.toBe(true);
    expect(store.authenticated()).toBe(true);
  });

  it('probe lands anonymous on 401 without toasting', async () => {
    const probe = store.probe();
    http.expectOne('/api/v1/auth/session').flush(
      { code: 'AUTH_REQUIRED', message: 'no session' },
      { status: 401, statusText: 'Unauthorized' },
    );
    await expect(probe).resolves.toBe(false);
    expect(store.auth()).toBe('anonymous');
  });

  it('login posts the gateway form body and authenticates on 204', () => {
    store.login('secret', '/home');
    const req = http.expectOne('/api/v1/auth/login');
    expect(req.request.headers.get('Content-Type')).toContain('x-www-form-urlencoded');
    expect(req.request.body).toBe('password=secret');
    req.flush(null, { status: 204, statusText: 'No Content' });
    expect(store.auth()).toBe('authenticated');
    expect(store.loggingIn()).toBe(false);
  });

  it('failed login surfaces the envelope message and stays anonymous', () => {
    store.login('wrong', '/');
    http
      .expectOne('/api/v1/auth/login')
      .flush(
        { code: 'AUTH_BAD_CREDENTIALS', message: 'bad password' },
        { status: 401, statusText: 'Unauthorized' },
      );
    expect(store.loginError()).toBe('bad password');
    expect(store.authenticated()).toBe(false);
  });

  it('theme toggle persists and flips the html class', () => {
    const before = store.theme();
    store.toggleTheme();
    expect(store.theme()).not.toBe(before);
    expect(localStorage.getItem('ay.theme')).toBe(store.theme());
    expect(document.documentElement.classList.contains('ay-light')).toBe(
      store.theme() === 'light',
    );
  });

  it('system status MOCK lights the mock banner', () => {
    store.refreshSystemStatus();
    http.expectOne('/api/v1/system/status').flush({ kite: { session: 'MOCK' } });
    expect(store.mockMode()).toBe(true);
  });
});
