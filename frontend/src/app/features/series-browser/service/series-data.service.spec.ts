import {HttpTestingController} from '@angular/common/http/testing';
import {TestBed} from '@angular/core/testing';
import {afterEach, describe, expect, it} from 'vitest';

import {API_CONFIG} from '../../../core/config/api-config';
import {createAuthServiceStub, createQueryClientHarness, flushQueryAsync, flushSignalAndQueryEffects} from '../../../core/testing/query-testing';
import {AuthService} from '../../../shared/service/auth.service';
import {ReadStatus} from '../../book/model/book.model';
import {SeriesDataService} from './series-data.service';

function seriesDataServiceProviders() {
  return [
    ...createQueryClientHarness().providers,
    SeriesDataService,
    {provide: AuthService, useValue: createAuthServiceStub()},
  ];
}

describe('SeriesDataService', () => {
  afterEach(() => {
    TestBed.resetTestingModule();
  });

  it('sources series summaries from the server summary endpoint, not the full book collection', async () => {
    TestBed.configureTestingModule({providers: seriesDataServiceProviders()});

    const service = TestBed.inject(SeriesDataService);
    const httpTestingController = TestBed.inject(HttpTestingController);
    flushSignalAndQueryEffects();

    // SeriesDataService also runs the sidebar's series-count facet query eagerly - not this
    // test's concern, but it must be drained for httpTestingController.verify() to pass.
    httpTestingController.expectOne(req => req.url.endsWith('/api/v1/books/facets')).flush({facets: []});

    const request = httpTestingController.expectOne(`${API_CONFIG.BASE_URL}/api/v1/books/series/summary`);
    expect(request.request.method).toBe('GET');
    request.flush([{
      seriesName: 'Alpha',
      bookCount: 2,
      readCount: 1,
      progress: 0.5,
      seriesStatus: 'PARTIALLY_READ',
      nextUnreadBookId: 1,
      lastReadTime: '2026-03-26T10:00:00Z',
      addedOn: '2026-03-26T09:00:00Z',
      authors: ['Bert', 'Cy', 'Ada'],
      categories: ['Fantasy', 'Sci-Fi'],
      coverBooks: [
        {bookId: 2, bookType: 'EPUB', coverUpdatedOn: '2026-03-26T09:00:00Z'},
        {bookId: 1, bookType: 'EPUB', coverUpdatedOn: '2026-03-25T09:00:00Z'},
      ],
    }]);

    await flushQueryAsync();

    const summaries = service.allSeries();
    expect(summaries).toHaveLength(1);
    expect(summaries[0]).toMatchObject({
      seriesName: 'Alpha',
      bookCount: 2,
      readCount: 1,
      progress: 0.5,
      seriesStatus: ReadStatus.PARTIALLY_READ,
      nextUnreadBookId: 1,
    });
    expect(summaries[0].authors).toEqual(['Bert', 'Cy', 'Ada']);
    expect(summaries[0].coverBooks.map(book => book.id)).toEqual([2, 1]);

    httpTestingController.verify();
  });
});
