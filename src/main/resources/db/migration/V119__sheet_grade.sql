-- The grades added to a sheet beyond A, B and C, one row each: a code of one to three capital letters.
-- Grade columns are widened to take such a code.
CREATE TABLE sheet_grade (
    id       BIGINT AUTO_INCREMENT PRIMARY KEY,
    sheet_id VARCHAR(100) NOT NULL REFERENCES sheet (id),
    grade    VARCHAR(3)   NOT NULL,
    CONSTRAINT sheet_grade_unique UNIQUE (sheet_id, grade)
);
ALTER TABLE person_grade ALTER COLUMN grade SET DATA TYPE VARCHAR(3);
ALTER TABLE rate ALTER COLUMN grade SET DATA TYPE VARCHAR(3);
ALTER TABLE sheet ALTER COLUMN default_grade SET DATA TYPE VARCHAR(3);
