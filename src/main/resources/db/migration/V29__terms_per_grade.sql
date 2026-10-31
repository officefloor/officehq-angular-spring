-- Terms can be set per grade: a grade without its own terms uses the sheet's terms.
CREATE TABLE sheet_grade_terms (
    sheet_id VARCHAR(100) NOT NULL REFERENCES sheet (id),
    grade    VARCHAR(1)   NOT NULL,
    terms    INT          NOT NULL CHECK (terms >= 0),
    PRIMARY KEY (sheet_id, grade)
);

-- A claim's due-by is fixed when it is added: its date plus the terms of its person's grade in force then.
-- Existing claims take their date plus the sheet's terms, as they were shown until now.
ALTER TABLE claim ADD COLUMN due_by DATE;
UPDATE claim SET due_by = DATEADD(DAY, (SELECT s.terms FROM sheet s WHERE s.id = claim.sheet_id), claim_date);
ALTER TABLE claim ALTER COLUMN due_by SET NOT NULL;
