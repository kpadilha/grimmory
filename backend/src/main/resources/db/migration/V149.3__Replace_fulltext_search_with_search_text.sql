-- Every statement is idempotent: MariaDB auto-commits DDL, so a rerun after an interruption must
-- succeed from any point. V149.5 fills the new table, so nothing here reads the old column.
DROP INDEX IF EXISTS ft_book_metadata_title_series ON book_metadata;
DROP INDEX IF EXISTS ft_author_name ON author;
DROP INDEX IF EXISTS ft_category_name ON category;
DROP INDEX IF EXISTS ft_tag_name ON tag;
DROP INDEX IF EXISTS idx_book_metadata_isbn_13 ON book_metadata;
DROP INDEX IF EXISTS idx_book_metadata_isbn_10 ON book_metadata;
DROP INDEX IF EXISTS idx_book_metadata_asin ON book_metadata;

-- book_metadata rows carry descriptions and embeddings and outgrow the buffer pool; a narrow table
-- keeps the per-row search probe in memory.
CREATE TABLE IF NOT EXISTS book_metadata_search (
    book_id         BIGINT NOT NULL PRIMARY KEY,
    search_text     TEXT,
    search_phonetic TEXT,
    CONSTRAINT fk_book_metadata_search FOREIGN KEY (book_id) REFERENCES book_metadata (book_id) ON DELETE CASCADE
);
