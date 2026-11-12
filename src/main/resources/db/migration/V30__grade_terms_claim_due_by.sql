-- Terms per grade: a grade with its own terms has its claims due that many days after their date; a grade
-- without uses the sheet's terms.
CREATE TABLE sheet_grade_terms (
    sheet_id VARCHAR(200) NOT NULL REFERENCES sheet (id),
    grade    VARCHAR(1)   NOT NULL CHECK (grade IN ('A', 'B', 'C')),
    terms    INT          NOT NULL CHECK (terms >= 0),
    PRIMARY KEY (sheet_id, grade)
);
-- A claim's due-by is fixed when it is added: its date plus the terms of its person's grade then in force.
-- Existing claims take their date plus their sheet's terms now.
ALTER TABLE claim ADD COLUMN due_by DATE;
UPDATE claim SET due_by = DATEADD(DAY, (SELECT s.terms FROM sheet s WHERE s.id = claim.sheet_id), claim_date);
ALTER TABLE claim ALTER COLUMN due_by SET NOT NULL;
