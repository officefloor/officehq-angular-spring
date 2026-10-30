-- Terms per grade: a grade with its own terms (days) uses them, one without (null) uses the sheet's.
ALTER TABLE sheet ADD COLUMN terms_a INT;
ALTER TABLE sheet ADD COLUMN terms_b INT;
ALTER TABLE sheet ADD COLUMN terms_c INT;
-- A claim's due-by, fixed when the claim is added; a claim already there keeps the one it had.
ALTER TABLE claim ADD COLUMN due_by DATE;
UPDATE claim SET due_by = DATEADD(DAY, (SELECT s.terms FROM sheet s WHERE s.id = claim.sheet_id), claim_date);
UPDATE claim SET due_by = DATEADD(DAY, 14, claim_date) WHERE due_by IS NULL;
ALTER TABLE claim ALTER COLUMN due_by SET NOT NULL;
