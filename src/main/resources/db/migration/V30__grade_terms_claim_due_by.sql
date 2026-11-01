-- Terms can be set per grade: a grade with a row here uses its terms, any other the sheet's terms.
CREATE TABLE sheet_grade_terms (
    sheet_id VARCHAR(100) NOT NULL REFERENCES sheet (id),
    grade    CHAR(1)      NOT NULL,
    terms    INT          NOT NULL,
    PRIMARY KEY (sheet_id, grade)
);

-- A claim's due-by is fixed when it is added. Existing claims keep the due-by they show: their
-- date plus their sheet's terms.
ALTER TABLE claim ADD COLUMN due_by DATE;
UPDATE claim SET due_by = DATEADD(DAY, (SELECT s.terms FROM sheet s WHERE s.id = claim.sheet_id), claim_date);
ALTER TABLE claim ALTER COLUMN due_by SET NOT NULL;
