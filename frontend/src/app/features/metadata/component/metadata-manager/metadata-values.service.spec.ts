import {HttpTestingController} from '@angular/common/http/testing';
import {TestBed} from '@angular/core/testing';
import {afterEach, beforeEach, describe, expect, it} from 'vitest';

import {createQueryClientHarness} from '../../../../core/testing/query-testing';
import {metadataValueTypeahead, MetadataValuesService} from './metadata-values.service';

describe('MetadataValuesService.search', () => {
  let service: MetadataValuesService;
  let http: HttpTestingController;

  beforeEach(() => {
    TestBed.configureTestingModule({providers: [...createQueryClientHarness().providers, MetadataValuesService]});
    service = TestBed.inject(MetadataValuesService);
    http = TestBed.inject(HttpTestingController);
  });

  afterEach(() => http.verify());

  it('sends field, query, and limit to the capped typeahead endpoint, not /facets/values', () => {
    service.search('author', 'le gu', 20).subscribe();

    const request = http.expectOne(req => req.url.endsWith('/api/v1/books/metadata-values'));
    expect(request.request.params.get('field')).toBe('author');
    expect(request.request.params.get('query')).toBe('le gu');
    expect(request.request.params.get('limit')).toBe('20');
    request.flush(['Ursula Le Guin']);
  });
});

describe('metadataValueTypeahead', () => {
  let http: HttpTestingController;

  beforeEach(() => {
    TestBed.configureTestingModule({providers: [...createQueryClientHarness().providers, MetadataValuesService]});
    http = TestBed.inject(HttpTestingController);
  });

  afterEach(() => http.verify());

  it('stays empty until the caller drives a query - never fetches on its own', () => {
    const typeahead = TestBed.runInInjectionContext(() => metadataValueTypeahead('author'));

    expect(typeahead.results()).toEqual([]);
    http.expectNone(req => req.url.endsWith('/api/v1/books/metadata-values'));
  });

  it('renders the response after a query, scoped to the given field', () => {
    const typeahead = TestBed.runInInjectionContext(() => metadataValueTypeahead('genre'));

    typeahead.filter({query: 'fan'});

    const request = http.expectOne(req => req.url.endsWith('/api/v1/books/metadata-values'));
    expect(request.request.params.get('field')).toBe('genre');
    expect(request.request.params.get('query')).toBe('fan');
    request.flush(['Fantasy']);

    expect(typeahead.results()).toEqual(['Fantasy']);
  });

  it('drops a stale response overtaken by a newer query, switchMap-style', () => {
    const typeahead = TestBed.runInInjectionContext(() => metadataValueTypeahead('author'));

    typeahead.filter({query: 'al'});
    const stale = http.expectOne(req => req.url.endsWith('/api/v1/books/metadata-values'));

    typeahead.filter({query: 'ali'});
    expect(stale.cancelled).toBe(true);

    const fresh = http.expectOne(req => req.url.endsWith('/api/v1/books/metadata-values'));
    fresh.flush(['Alice']);

    expect(typeahead.results()).toEqual(['Alice']);
  });
});
