-- The sheet's policy date: a claim dated before it rounds its live figures half up, one dated on or after it half to
-- even. Null (blank) means the sheet's today.
ALTER TABLE sheet ADD COLUMN policy_date DATE;
-- The date that decides how a claim rounds: its own date, or for a correction the date of the claim it corrects.
ALTER TABLE claim ADD COLUMN rounding_date DATE;
UPDATE claim SET rounding_date = claim_date;
UPDATE claim c SET rounding_date = (SELECT o.claim_date FROM claim o WHERE o.sheet_id = c.sheet_id
    AND o.person_id = c.person_id AND o.claim_number = c.corrects) WHERE c.corrects IS NOT NULL
    AND EXISTS (SELECT 1 FROM claim o WHERE o.sheet_id = c.sheet_id AND o.person_id = c.person_id
    AND o.claim_number = c.corrects);
ALTER TABLE claim ALTER COLUMN rounding_date SET NOT NULL;
