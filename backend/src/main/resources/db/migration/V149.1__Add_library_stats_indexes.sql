-- Support the library-stats server-side aggregation queries (LibraryStatsService), which replace
-- a full whole-library book load with grouped/bucketed counts scoped by these columns.
CREATE INDEX IF NOT EXISTS idx_book_metadata_page_count ON book_metadata (page_count);
CREATE INDEX IF NOT EXISTS idx_book_metadata_published_date ON book_metadata (published_date);
CREATE INDEX IF NOT EXISTS idx_book_metadata_match_score ON book (metadata_match_score);
CREATE INDEX IF NOT EXISTS idx_book_added_on ON book (added_on);
CREATE INDEX IF NOT EXISTS idx_book_file_book_type ON book_file (book_type);
CREATE INDEX IF NOT EXISTS idx_user_book_progress_user_read_status ON user_book_progress (user_id, read_status);
CREATE INDEX IF NOT EXISTS idx_user_book_progress_user_date_finished ON user_book_progress (user_id, date_finished);
