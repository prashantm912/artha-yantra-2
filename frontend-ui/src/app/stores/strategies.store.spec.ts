import { TestBed } from '@angular/core/testing';
import { provideHttpClient } from '@angular/common/http';
import { HttpTestingController, provideHttpClientTesting } from '@angular/common/http/testing';
import { describe, expect, it, vi, beforeEach, afterEach } from 'vitest';
import { StrategiesStore } from './strategies.store';

describe('StrategiesStore', () => {
  let http: HttpTestingController;
  let store: InstanceType<typeof StrategiesStore>;

  beforeEach(() => {
    TestBed.configureTestingModule({
      providers: [provideHttpClient(), provideHttpClientTesting()],
    });
    http = TestBed.inject(HttpTestingController);
    store = TestBed.inject(StrategiesStore);
  });

  afterEach(() => http.verify());

  it('loadList populates the list', () => {
    store.loadList();
    http
      .expectOne((r) => r.url === '/api/v1/strategies')
      .flush({
        items: [
          { id: 'a', slug: 'ema', name: 'EMA', status: 'draft', currentVersion: '1.0.0', tags: [] },
        ],
      });
    expect(store.list().map((s) => s.id)).toEqual(['a']);
  });

  it('loadDetail seeds the draft buffer from configYaml and is not dirty', () => {
    store.loadDetail('a');
    http
      .expectOne((r) => r.url === '/api/v1/strategies/a')
      .flush({
        id: 'a',
        name: 'EMA',
        version: '1.0.0',
        status: 'draft',
        configYaml: 'schema: strategy-schema/v1\n',
      });
    expect(store.draft()).toBe('schema: strategy-schema/v1\n');
    expect(store.dirty()).toBe(false);
  });

  it('setDraft marks the buffer dirty', () => {
    store.setDraft('schema: strategy-schema/v1\nid: x\n');
    expect(store.dirty()).toBe(true);
  });

  it('validate stores the server result with error paths', () => {
    store.setDraft('bad: yaml');
    store.validate();
    http
      .expectOne((r) => r.method === 'POST' && r.url === '/api/v1/strategies/validate')
      .flush({ valid: false, errors: [{ path: '/id', message: 'required' }], warnings: [] });
    expect(store.validation()?.valid).toBe(false);
    expect(store.validation()?.errors[0].path).toBe('/id');
  });

  it('saveDraft (existing id) PUTs and clears dirty', () => {
    store.setDraft('schema: strategy-schema/v1\nid: x\n');
    store.saveDraft('a', 'minor', 'tweak');
    const req = http.expectOne((r) => r.method === 'PUT' && r.url === '/api/v1/strategies/a');
    expect(req.request.body.versionBump).toBe('minor');
    req.flush({ id: 'a', version: '1.1.0', status: 'draft', checksum: 'abc' });
    expect(store.dirty()).toBe(false);
    expect(store.lastSaved()?.version).toBe('1.1.0');
  });

  it('create POSTs and yields the new id to the callback', () => {
    const then = vi.fn();
    store.create({ name: 'New', config: 'schema: strategy-schema/v1\n' }, then);
    http
      .expectOne((r) => r.method === 'POST' && r.url === '/api/v1/strategies')
      .flush({ id: 'new-id', version: '1.0.0', status: 'draft', checksum: 'z' });
    expect(then).toHaveBeenCalledWith('new-id');
    expect(store.createdId()).toBe('new-id');
  });

  it('loadSchema stores the JSON schema', () => {
    store.loadSchema();
    http.expectOne((r) => r.url === '/api/v1/strategies/schema/v1').flush({ type: 'object' });
    expect(store.schema()).toEqual({ type: 'object' });
  });

  it('loadVersions populates the version timeline', () => {
    store.loadVersions('a');
    http
      .expectOne((r) => r.url === '/api/v1/strategies/a/versions')
      .flush({
        items: [
          { version: '1.1.0', status: 'draft', checksum: 'x' },
          { version: '1.0.0', status: 'published', checksum: 'y' },
        ],
      });
    expect(store.versions().map((v) => v.version)).toEqual(['1.1.0', '1.0.0']);
  });

  it('loadDiff stores the structured diff and both YAMLs', () => {
    store.loadDiff('a', '1.0.0', '1.1.0');
    const req = http.expectOne((r) => r.url === '/api/v1/strategies/a/diff');
    expect(req.request.params.get('from')).toBe('1.0.0');
    req.flush({
      structured: [{ path: 'scoring.threshold', op: 'change', before: '0.5', after: '0.6' }],
      yamlFrom: 'a: 1',
      yamlTo: 'a: 2',
    });
    expect(store.diff()?.structured[0].op).toBe('change');
    expect(store.diff()?.yamlTo).toBe('a: 2');
  });

  it('publish POSTs and yields the published version to the callback', () => {
    const then = vi.fn();
    store.publish('a', { notes: 'go live' }, then);
    http
      .expectOne((r) => r.method === 'POST' && r.url === '/api/v1/strategies/a/publish')
      .flush({ id: 'a', version: '1.1.0', status: 'published' });
    // publish refreshes the timeline
    http.expectOne((r) => r.url === '/api/v1/strategies/a/versions').flush({ items: [] });
    expect(then).toHaveBeenCalledWith('1.1.0');
  });

  it('setNotifications PATCHes and updates the list row', () => {
    store.loadList();
    http
      .expectOne((r) => r.url === '/api/v1/strategies')
      .flush({ items: [{ id: 'a', slug: 'ema', name: 'EMA', status: 'published', tags: [] }] });
    store.setNotifications('a', true, 'NTFY');
    const req = http.expectOne(
      (r) => r.method === 'PATCH' && r.url === '/api/v1/strategies/a/notifications',
    );
    expect(req.request.body.enabled).toBe(true);
    req.flush({ notificationsEnabled: true, notificationChannel: 'NTFY' });
    expect(store.list()[0].notificationsEnabled).toBe(true);
    expect(store.list()[0].notificationChannel).toBe('NTFY');
  });

  it('testNotification POSTs to the test-send route', () => {
    const then = vi.fn();
    store.testNotification('a', then);
    http
      .expectOne((r) => r.method === 'POST' && r.url === '/api/v1/strategies/a/notifications/test')
      .flush({ status: 'SENT' });
    expect(then).toHaveBeenCalled();
  });

  it('rollback POSTs the source version and yields the new draft', () => {
    const then = vi.fn();
    store.rollback('a', { version: '1.0.0' }, then);
    const req = http.expectOne(
      (r) => r.method === 'POST' && r.url === '/api/v1/strategies/a/rollback',
    );
    expect(req.request.body.version).toBe('1.0.0');
    req.flush({ id: 'a', newVersion: '1.2.0', copiedFrom: '1.0.0', status: 'draft' });
    http.expectOne((r) => r.url === '/api/v1/strategies/a/versions').flush({ items: [] });
    expect(then).toHaveBeenCalledWith('1.2.0');
  });
});
