-- The sheet's policy date: a claim rounded by a date before it rounds half up, one on or after it half to even.
-- Null (blank) means the sheet's today.
ALTER TABLE sheet ADD COLUMN policy_date DATE;
-- The date a claim's rounding follows: its own date, or for a correction the date of the claim it corrects.
ALTER TABLE claim ADD COLUMN rounds_on DATE;
UPDATE claim SET rounds_on = claim_date;
UPDATE claim c SET rounds_on = COALESCE((SELECT o.claim_date FROM claim o
    WHERE o.sheet_id = c.sheet_id AND o.claim_number = c.corrects), c.claim_date)
    WHERE c.corrects IS NOT NULL;
ALTER TABLE claim ALTER COLUMN rounds_on SET NOT NULL;
