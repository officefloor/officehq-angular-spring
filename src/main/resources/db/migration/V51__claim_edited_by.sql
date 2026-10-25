-- Who last edited a claim (through its form or its row's amount entry): the signed-in user's name,
-- set on each saved edit. Null when it was never edited. Like its opener, the user who last edited a
-- claim may not settle or approve it.
ALTER TABLE claim ADD COLUMN edited_by VARCHAR(255);
