-- Terms per grade: a grade with its own terms uses them; a grade without uses the sheet's terms.
CREATE TABLE sheet_grade_terms (
    sheet_id VARCHAR(100) NOT NULL REFERENCES sheet (id),
    grade    VARCHAR(1)   NOT NULL CHECK (grade IN ('A', 'B', 'C')),
    terms    INTEGER      NOT NULL CHECK (terms >= 0),
    PRIMARY KEY (sheet_id, grade)
);
-- A claim's due-by is fixed when it is created, from the terms of its person's grade in force on
-- its date; later changes to the terms or the claim's date leave it as it is. Existing claims are
-- due by their date plus the sheet's terms, as they were.
ALTER TABLE claim ADD COLUMN due_by DATE;
UPDATE claim SET due_by = DATEADD(DAY, (SELECT s.terms FROM sheet s WHERE s.id = claim.sheet_id), claim_date);
ALTER TABLE claim ALTER COLUMN due_by SET NOT NULL;
