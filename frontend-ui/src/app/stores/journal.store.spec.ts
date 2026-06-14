import { TestBed } from '@angular/core/testing';
import { provideHttpClient } from '@angular/common/http';
import { HttpTestingController, provideHttpClientTesting } from '@angular/common/http/testing';
import { describe, expect, it, beforeEach, afterEach } from 'vitest';
import { JournalStore } from './journal.store';

describe('JournalStore', () => {
  let http: HttpTestingController;
  let store: InstanceType<typeof JournalStore>;

  beforeEach(() => {
    TestBed.configureTestingModule({
      providers: [provideHttpClient(), provideHttpClientTesting()],
    });
    http = TestBed.inject(HttpTestingController);
    store = TestBed.inject(JournalStore);
  });

  afterEach(() => http.verify());

  it('load populates the entry list', () => {
    store.load();
    http
      .expectOne((r) => r.url === '/api/v1/journal')
      .flush({
        items: [
          {
            id: 1,
            note: 'x',
            tags: ['fomo'],
            disciplineRating: 2,
            emotionRating: null,
            createdAt: 'd',
            updatedAt: 'd',
          },
        ],
      });
    expect(store.items()).toHaveLength(1);
    expect(store.items()[0].tags).toEqual(['fomo']);
  });

  it('setTag adds the filter and reloads', () => {
    store.setTag('fomo');
    const req = http.expectOne((r) => r.url === '/api/v1/journal');
    expect(req.request.params.get('tag')).toBe('fomo');
    req.flush({ items: [] });
  });

  it('create posts the entry then reloads', () => {
    store.create({ signalId: 7, note: 'rushed', tags: ['fomo'] });
    const post = http.expectOne((r) => r.method === 'POST' && r.url === '/api/v1/journal');
    expect(post.request.body).toEqual({ signalId: 7, note: 'rushed', tags: ['fomo'] });
    post.flush({ id: 3 });
    http.expectOne((r) => r.url === '/api/v1/journal').flush({ items: [] });
  });

  it('remove deletes then reloads', () => {
    store.remove(3);
    http.expectOne((r) => r.method === 'DELETE' && r.url === '/api/v1/journal/3').flush(null);
    http.expectOne((r) => r.url === '/api/v1/journal').flush({ items: [] });
  });
});
