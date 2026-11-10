-- The user who last edited a claim (through its form or its row's amount); none while never edited.
-- That user, like its opener, cannot settle or approve it (four eyes).
ALTER TABLE claim ADD COLUMN edited_by VARCHAR(100);
