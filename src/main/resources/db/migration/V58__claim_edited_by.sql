-- Who last edited a claim: the signed-in user who last saved it through the claim form or the row's
-- amount entry. None while never edited. That user cannot settle or approve it (four eyes).
ALTER TABLE claim ADD COLUMN edited_by VARCHAR(200);
