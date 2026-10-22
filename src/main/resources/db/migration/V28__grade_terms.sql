-- Terms per grade: a grade's own terms in days; null means the grade uses the sheet's terms.
ALTER TABLE sheet ADD COLUMN terms_a INT;
ALTER TABLE sheet ADD COLUMN terms_b INT;
ALTER TABLE sheet ADD COLUMN terms_c INT;
-- A claim's due-by is fixed when it is added, from the terms of its person's grade in force on its
-- date; later changes to the terms or the claim's date do not move it. Existing claims take their
-- date plus the sheet's terms.
ALTER TABLE claim ADD COLUMN due_by DATE;
UPDATE claim c SET due_by = DATEADD(DAY, (SELECT s.terms FROM sheet s WHERE s.id = c.sheet_id), c.claim_date);
ALTER TABLE claim ALTER COLUMN due_by SET NOT NULL;
