-- ASIN stays Audible-only (the UI links it to Amazon and Audible); OpenLibrary matches keep their own id.
ALTER TABLE author ADD COLUMN IF NOT EXISTS openlibrary_id VARCHAR(20);
