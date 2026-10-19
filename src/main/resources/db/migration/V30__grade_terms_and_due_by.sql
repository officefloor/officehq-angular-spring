-- A sheet's terms per grade: the days after its date a claim of a person of that grade is due by. A grade
-- without its own terms uses the sheet's terms.
CREATE TABLE sheet_grade_terms (
    sheet_id   VARCHAR(100) NOT NULL REFERENCES sheet (id),
    grade      VARCHAR(10)  NOT NULL,
    terms_days INT          NOT NULL CHECK (terms_days >= 0),
    PRIMARY KEY (sheet_id, grade)
);
-- A claim's due-by is fixed when it is added, from the terms in force then for its person's grade on its date;
-- a claim already there is due by its date plus the sheet's terms now.
ALTER TABLE claim ADD COLUMN due_by DATE;
UPDATE claim c SET due_by = DATEADD(DAY, (SELECT s.terms_days FROM sheet s WHERE s.id = c.sheet_id), c.claim_date);
ALTER TABLE claim ALTER COLUMN due_by SET NOT NULL;
