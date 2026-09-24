-- title is varchar(1000) utf8mb4: too wide for a full-column key, and a prefix index cannot serve
-- ORDER BY. A 255-char stored copy inherits the table collation, so it orders exactly like title.
ALTER TABLE book_metadata
    ADD COLUMN IF NOT EXISTS title_sort VARCHAR(255) AS (LEFT(title, 255)) STORED;

CREATE INDEX IF NOT EXISTS idx_book_metadata_title_sort ON book_metadata (title_sort, book_id);
