-- The signed-in name of the user who last edited a claim (through the claim form or its row's amount entry);
-- none while it has never been edited. Like its opener, that user cannot settle or approve it.
ALTER TABLE claim ADD COLUMN edited_by VARCHAR(100);
