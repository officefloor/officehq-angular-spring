-- The user who last edited a claim (through its form or its row's amount entry); none until
-- edited. Like its opener, that user cannot settle or approve it.
ALTER TABLE claim ADD COLUMN edited_by VARCHAR(100);
