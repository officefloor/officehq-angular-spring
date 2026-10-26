-- The user who last edited a claim (its form or its amount entry); null for a claim never edited. That user, like its
-- opener, may not settle or approve it.
ALTER TABLE claim ADD COLUMN edited_by VARCHAR(100);
