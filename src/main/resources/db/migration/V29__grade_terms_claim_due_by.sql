-- Terms set per grade: a grade without its own terms uses the sheet's terms.
CREATE TABLE sheet_grade_terms (
    sheet_id VARCHAR(100) NOT NULL REFERENCES sheet (id),
    grade    VARCHAR(1)   NOT NULL CHECK (grade IN ('A', 'B', 'C')),
    terms    INT          NOT NULL CHECK (terms >= 0),
    PRIMARY KEY (sheet_id, grade)
);
-- A claim's due-by is fixed when it is created: its date plus the terms of its person's grade in
-- force on that date. It does not change when the terms or the claim's date change later.
ALTER TABLE claim ADD COLUMN due_by DATE;
-- Claims created before this change take the due-by they show now.
UPDATE claim c SET due_by = DATEADD(DAY, (SELECT s.terms FROM sheet s WHERE s.id = c.sheet_id), c.claim_date);
ALTER TABLE claim ALTER COLUMN due_by SET NOT NULL;
