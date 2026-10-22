-- The user who last edited the claim (through its form or its row's amount entry); null when never
-- edited. Like its opener, that user cannot settle or approve it.
ALTER TABLE claim ADD COLUMN edited_by VARCHAR(200);
