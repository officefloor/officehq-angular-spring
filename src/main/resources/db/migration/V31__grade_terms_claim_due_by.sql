-- A grade's own terms on a sheet: how many days after its date a claim of a person of that grade is
-- due by. A grade with no row uses the sheet's terms.
CREATE TABLE sheet_grade_terms (
    id       BIGINT AUTO_INCREMENT PRIMARY KEY,
    sheet_id VARCHAR(100) NOT NULL REFERENCES sheet (id),
    grade    VARCHAR(1)   NOT NULL,
    terms    INTEGER      NOT NULL CHECK (terms >= 0)
);
CREATE UNIQUE INDEX sheet_grade_terms_sheet_grade ON sheet_grade_terms (sheet_id, grade);

-- A claim's due-by, fixed when it is added: its date plus the terms of its person's grade in force
-- on that date. It does not follow later changes to the terms or to the claim's date. The claims
-- already kept are given their date plus their sheet's terms.
ALTER TABLE claim ADD COLUMN due_by DATE;
UPDATE claim c SET due_by = DATEADD(DAY, (SELECT s.terms FROM sheet s WHERE s.id = c.sheet_id), c.claim_date);
ALTER TABLE claim ALTER COLUMN due_by SET NOT NULL;
