-- A sheet's added grades: beside A, B and C, a sheet may add grades of its own, each a code of one to
-- three capital letters, kept in the order they were added. A grade is added on a sheet at most once.
CREATE TABLE sheet_grade (
    id BIGINT AUTO_INCREMENT PRIMARY KEY,
    sheet_id VARCHAR(200) NOT NULL REFERENCES sheet (id),
    grade VARCHAR(3) NOT NULL
);
CREATE UNIQUE INDEX sheet_grade_sheet_grade ON sheet_grade (sheet_id, grade);

-- A grade is up to three letters wherever one is kept.
ALTER TABLE rate ALTER COLUMN grade SET DATA TYPE VARCHAR(3);
ALTER TABLE rate_history ALTER COLUMN grade SET DATA TYPE VARCHAR(3);
ALTER TABLE person_grade ALTER COLUMN grade SET DATA TYPE VARCHAR(3);
ALTER TABLE person_grade_history ALTER COLUMN grade SET DATA TYPE VARCHAR(3);
ALTER TABLE retired_grade ALTER COLUMN grade SET DATA TYPE VARCHAR(3);
ALTER TABLE sheet ALTER COLUMN default_grade SET DATA TYPE VARCHAR(3);
