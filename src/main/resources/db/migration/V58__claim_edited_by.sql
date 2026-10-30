-- The user who last edited the claim (through its form or its amount entry); null while never edited.
ALTER TABLE claim ADD COLUMN edited_by VARCHAR(100);
