-- Grades can be added to a sheet: a code of one to three capital letters, beside A, B and C which every sheet has.
-- An added grade is a grade like any other on that sheet. Not dated.
CREATE TABLE sheet_grade_added (
    sheet_id VARCHAR(100) NOT NULL REFERENCES sheet (id),
    grade    VARCHAR(3)   NOT NULL,
    PRIMARY KEY (sheet_id, grade)
);
ALTER TABLE person_grade ALTER COLUMN grade VARCHAR(3) NOT NULL;
ALTER TABLE rate ALTER COLUMN grade VARCHAR(3) NOT NULL;
ALTER TABLE sheet_grade_retired ALTER COLUMN grade VARCHAR(3) NOT NULL;
ALTER TABLE sheet ALTER COLUMN default_grade VARCHAR(3);
