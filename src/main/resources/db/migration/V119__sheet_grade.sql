-- The grades added on a sheet, beyond the standing A, B and C every sheet has: a code of one to three
-- capital letters. One row per sheet and grade. Every column holding a grade widens to take such a code.
CREATE TABLE sheet_grade (
    sheet_id VARCHAR(100) NOT NULL REFERENCES sheet (id),
    grade    VARCHAR(3)   NOT NULL,
    PRIMARY KEY (sheet_id, grade)
);
ALTER TABLE person ALTER COLUMN grade SET DATA TYPE VARCHAR(3);
ALTER TABLE person_grade ALTER COLUMN grade SET DATA TYPE VARCHAR(3);
ALTER TABLE person_grade_log ALTER COLUMN grade SET DATA TYPE VARCHAR(3);
ALTER TABLE rate ALTER COLUMN grade SET DATA TYPE VARCHAR(3);
ALTER TABLE rate_log ALTER COLUMN grade SET DATA TYPE VARCHAR(3);
ALTER TABLE sheet ALTER COLUMN default_grade SET DATA TYPE VARCHAR(3);
ALTER TABLE sheet_retired_grade ALTER COLUMN grade SET DATA TYPE VARCHAR(3);
