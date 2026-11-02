-- The sheet's policy date: a claim dated before it rounds half up, one on or after it half to even;
-- null (blank) takes the sheet's today.
ALTER TABLE sheet ADD COLUMN policy_date DATE;
-- A correction rounds by the date of the claim it corrects, kept with it; null for a claim that is
-- not a correction.
ALTER TABLE claim ADD COLUMN corrected_date DATE;
UPDATE claim c SET corrected_date = (SELECT o.claim_date FROM claim o WHERE o.sheet_id = c.sheet_id
    AND o.person_id = c.person_id AND o.number = c.corrects AND o.status = 'SETTLED')
    WHERE c.corrects IS NOT NULL;
