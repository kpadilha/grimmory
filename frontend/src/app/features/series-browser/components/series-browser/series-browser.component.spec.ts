import {describe, it} from 'vitest';

// NOTE(frontend-seam): Real coverage here needs seams around virtual-scroller rendering, page
// title initialization, and signal-driven search and filter state so the series browser can be
// tested without mounting the full browser shell. Search/sort/status now run server-side
// (SeriesSummaryService, covered in its own backend test) - what is left to seam here is the
// infinite-scroll wiring itself.
describe.skip('SeriesBrowserComponent', () => {
  it('needs browser-shell seams to verify infinite-scroll page loading triggers at the grid boundary', () => {
    // TODO(seam): Cover the load-more effect once the infinite query and virtual-scroller concerns are isolated behind adapters.
  });

  it('needs browser-shell seams to verify responsive card sizing, route navigation, and page-title behavior', () => {
    // TODO(seam): Cover ngOnInit and navigateToSeries after extracting router and virtual-scroller concerns behind adapters.
  });
});
