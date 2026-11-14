-- A claim records who opened it; that user cannot settle it (four eyes). None while DRAFT, and
-- none for claims opened before this change.
ALTER TABLE claim ADD COLUMN opened_by VARCHAR(100);
