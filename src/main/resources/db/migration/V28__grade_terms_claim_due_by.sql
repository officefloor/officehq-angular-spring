-- A grade's own terms on a sheet: the days after a claim's date it is due by, for a person of that
-- grade. A grade with no row uses the sheet's terms.
CREATE TABLE sheet_grade_terms (
    sheet_id VARCHAR(100) NOT NULL REFERENCES sheet (id),
    grade    CHAR(1)      NOT NULL CHECK (grade IN ('A', 'B', 'C')),
    terms    INT          NOT NULL,
    CONSTRAINT sheet_grade_terms_pk PRIMARY KEY (sheet_id, grade)
);

-- A claim's due-by, fixed when it is created; claims already kept take their date plus the sheet's terms.
ALTER TABLE claim ADD COLUMN due_by DATE;
UPDATE claim c SET due_by = DATEADD('DAY', (SELECT s.terms FROM sheet s WHERE s.id = c.sheet_id), c.claim_date);
ALTER TABLE claim ALTER COLUMN due_by SET NOT NULL;
