-- Who last edited the claim (null when never edited): like its opener, that user cannot settle or
-- approve it.
ALTER TABLE claim ADD COLUMN edited_by VARCHAR(100);
