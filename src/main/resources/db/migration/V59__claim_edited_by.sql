-- Who last edited a claim: the signed-in user who last saved its form or its amount entry; null for
-- a claim never edited. Like its opener, that user may not settle or approve it.
ALTER TABLE claim ADD COLUMN edited_by VARCHAR(200);
