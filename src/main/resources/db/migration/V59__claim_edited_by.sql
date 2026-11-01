-- Who last edited a claim (its form or its row's amount entry); none while never edited.
-- That user cannot settle or approve it.
ALTER TABLE claim ADD COLUMN edited_by VARCHAR(100);
