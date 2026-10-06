-- The series browser groups by LOWER(TRIM(series_name)); an indexed generated column lets it fetch
-- a page of series by that key instead of evaluating the expression on every book_metadata row.
ALTER TABLE book_metadata ADD COLUMN IF NOT EXISTS series_key VARCHAR(1000) GENERATED ALWAYS AS (LOWER(TRIM(series_name))) VIRTUAL;
CREATE INDEX IF NOT EXISTS idx_book_metadata_series_key ON book_metadata (series_key(768));
