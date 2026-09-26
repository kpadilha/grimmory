-- Superseded by book_metadata_search.search_text, recomputed for every book in V149.5.
ALTER TABLE book_metadata DROP COLUMN IF EXISTS search_text;
