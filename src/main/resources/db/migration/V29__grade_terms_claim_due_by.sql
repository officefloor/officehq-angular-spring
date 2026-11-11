-- Terms per grade: the days after its date a claim of a person of the grade is due by. A grade with no row uses the
-- sheet's terms.
CREATE TABLE grade_terms (
    id       BIGINT AUTO_INCREMENT PRIMARY KEY,
    sheet_id VARCHAR(100) NOT NULL REFERENCES sheet (id),
    grade    VARCHAR(1)   NOT NULL,
    terms    INT          NOT NULL CHECK (terms >= 0)
);
CREATE UNIQUE INDEX grade_terms_sheet_grade ON grade_terms (sheet_id, grade);
-- A claim's due-by, fixed when it is added: its date plus the terms of its person's grade on that date. Claims already
-- kept take their date plus the sheet's terms.
ALTER TABLE claim ADD COLUMN due_by DATE;
UPDATE claim SET due_by = DATEADD(DAY, (SELECT s.terms FROM sheet s WHERE s.id = claim.sheet_id), claim_date);
ALTER TABLE claim ALTER COLUMN due_by SET NOT NULL;
