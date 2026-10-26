-- Terms set for one grade: a claim whose person has that grade on its date is due by its date plus these days. A
-- grade with no row uses the sheet's terms.
CREATE TABLE grade_terms (
    id       BIGINT AUTO_INCREMENT PRIMARY KEY,
    sheet_id VARCHAR(100) NOT NULL REFERENCES sheet (id),
    grade    VARCHAR(1)   NOT NULL,
    terms    INT          NOT NULL CHECK (terms >= 0)
);
CREATE UNIQUE INDEX grade_terms_grade ON grade_terms (sheet_id, grade);

-- A claim's due-by is fixed when it is added, from the terms in force for its person's grade on its date; it no
-- longer follows later changes to the terms or to the claim's date. Claims already there are fixed at the sheet's
-- terms they followed until now.
ALTER TABLE claim ADD COLUMN due_by DATE;
UPDATE claim c SET due_by = DATEADD(DAY, (SELECT s.terms FROM sheet s WHERE s.id = c.sheet_id), c.claim_date);
ALTER TABLE claim ALTER COLUMN due_by SET NOT NULL;
