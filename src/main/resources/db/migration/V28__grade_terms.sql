-- Terms per grade: a grade with terms of its own has its claims due by their date plus these days;
-- one without uses the sheet's terms.
CREATE TABLE sheet_grade_terms (
    sheet_id VARCHAR(100) NOT NULL REFERENCES sheet (id),
    grade    VARCHAR(1)   NOT NULL CHECK (grade IN ('A', 'B', 'C')),
    terms    INT          NOT NULL
);
CREATE UNIQUE INDEX sheet_grade_terms_grade ON sheet_grade_terms (sheet_id, grade);
-- A claim's due-by, fixed when it is created: its date plus the terms of its person's grade in force
-- on its date. Later changes to the terms or to the claim's date do not alter it. Claims there are
-- already take their date plus the sheet's terms now.
ALTER TABLE claim ADD COLUMN due_by DATE;
UPDATE claim c SET due_by = DATEADD('DAY', (SELECT s.terms FROM sheet s WHERE s.id = c.sheet_id), c.claim_date);
ALTER TABLE claim ALTER COLUMN due_by SET NOT NULL;
