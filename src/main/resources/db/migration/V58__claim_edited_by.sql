-- The user who last edited a claim (through its form or its amount entry); null for one never edited.
ALTER TABLE claim ADD COLUMN edited_by VARCHAR(100);
