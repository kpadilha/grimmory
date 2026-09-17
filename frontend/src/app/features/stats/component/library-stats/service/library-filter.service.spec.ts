import {signal} from '@angular/core';
import {TestBed} from '@angular/core/testing';
import {afterEach, beforeEach, describe, expect, it, vi} from 'vitest';

import {LibraryFilterService} from './library-filter.service';
import {LibraryService} from '../../../../book/service/library.service';
import {Library} from '../../../../book/model/library.model';
import {TranslocoService} from '@jsverse/transloco';

describe('LibraryFilterService', () => {
  const libraries = signal<Library[]>([]);
  const translate = vi.fn((key: string) => key);

  beforeEach(() => {
    libraries.set([]);
    translate.mockClear();

    TestBed.configureTestingModule({
      providers: [
        LibraryFilterService,
        {provide: LibraryService, useValue: {libraries}},
        {provide: TranslocoService, useValue: {translate}},
      ]
    });
  });

  afterEach(() => {
    TestBed.resetTestingModule();
  });

  it('shows only the all-libraries option when there are no libraries', () => {
    const service = TestBed.inject(LibraryFilterService);

    expect(service.libraryOptions()).toEqual([
      {id: null, name: 'statsLibrary.libraryFilter.allLibraries'}
    ]);
    expect(service.selectedLibrary()).toBeNull();
  });

  it('sorts library options from the library service, not from the book list', () => {
    libraries.set([
      {id: 2, name: 'Beta', watch: false, paths: []} as Library,
      {id: 1, name: 'Alpha', watch: false, paths: []} as Library,
    ]);

    const service = TestBed.inject(LibraryFilterService);

    expect(service.libraryOptions()).toEqual([
      {id: null, name: 'statsLibrary.libraryFilter.allLibraries'},
      {id: 1, name: 'Alpha'},
      {id: 2, name: 'Beta'},
    ]);

    service.setSelectedLibrary(2);
    expect(service.selectedLibrary()).toBe(2);

    service.setSelectedLibrary(99);
    expect(service.selectedLibrary()).toBeNull();
  });
});
