-- Search is substring matching on search_text again; the FULLTEXT indexes and the exact-match
-- identifier indexes only served the prefix search that replaced it.
DROP INDEX IF EXISTS ft_book_metadata_title_series ON book_metadata;
DROP INDEX IF EXISTS ft_author_name ON author;
DROP INDEX IF EXISTS ft_category_name ON category;
DROP INDEX IF EXISTS ft_tag_name ON tag;
DROP INDEX IF EXISTS idx_book_metadata_isbn_13 ON book_metadata;
DROP INDEX IF EXISTS idx_book_metadata_isbn_10 ON book_metadata;
DROP INDEX IF EXISTS idx_book_metadata_asin ON book_metadata;

-- book_metadata rows carry descriptions and embeddings and outgrow the buffer pool; a narrow table
-- keeps the per-row search probe in memory. search_phonetic is filled by the application.
CREATE TABLE IF NOT EXISTS book_metadata_search (
    book_id         BIGINT NOT NULL PRIMARY KEY,
    search_text     TEXT,
    search_phonetic TEXT,
    CONSTRAINT fk_book_metadata_search FOREIGN KEY (book_id) REFERENCES book_metadata (book_id) ON DELETE CASCADE
);

INSERT IGNORE INTO book_metadata_search (book_id, search_text)
SELECT book_id, search_text FROM book_metadata;

ALTER TABLE book_metadata DROP COLUMN IF EXISTS search_text;
