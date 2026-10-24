-- Who last edited a claim; null while it has never been edited. Like its opener, the user who last
-- edited a claim cannot settle or approve it.
ALTER TABLE claim ADD COLUMN edited_by VARCHAR(100);
