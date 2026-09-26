-- Built without the stopword list, so words like "the", "who" or "will" stay searchable; the
-- setting is recorded per index at creation time and only affects this migration's session.
SET SESSION innodb_ft_enable_stopword = OFF;

CREATE FULLTEXT INDEX IF NOT EXISTS ft_book_metadata_title_series ON book_metadata (title, series_name);
CREATE FULLTEXT INDEX IF NOT EXISTS ft_author_name ON author (name);
CREATE FULLTEXT INDEX IF NOT EXISTS ft_category_name ON category (name);
CREATE FULLTEXT INDEX IF NOT EXISTS ft_tag_name ON tag (name);

-- Exact identifier matches in the same search.
CREATE INDEX IF NOT EXISTS idx_book_metadata_isbn_13 ON book_metadata (isbn_13);
CREATE INDEX IF NOT EXISTS idx_book_metadata_isbn_10 ON book_metadata (isbn_10);
CREATE INDEX IF NOT EXISTS idx_book_metadata_asin ON book_metadata (asin);
