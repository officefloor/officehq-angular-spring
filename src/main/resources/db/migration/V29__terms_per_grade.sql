-- Terms per grade: the days after its date a claim of a person of the grade is due by; a grade with
-- none here takes the sheet's terms.
CREATE TABLE grade_terms (
    sheet_id VARCHAR(100) NOT NULL,
    grade VARCHAR(20) NOT NULL,
    terms INT NOT NULL,
    PRIMARY KEY (sheet_id, grade)
);
-- A claim's due-by, fixed when it is created; claims already made take their date plus the sheet's
-- terms.
ALTER TABLE claim ADD COLUMN due_by DATE;
UPDATE claim c SET due_by = DATEADD(DAY, (SELECT s.terms FROM sheet s WHERE s.id = c.sheet_id), c.claim_date);
UPDATE claim SET due_by = DATEADD(DAY, 14, claim_date) WHERE due_by IS NULL;
ALTER TABLE claim ALTER COLUMN due_by SET NOT NULL;
