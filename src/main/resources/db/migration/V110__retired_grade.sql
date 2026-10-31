-- A sheet's retired grades: a grade, at most once per sheet. A retired grade cannot be chosen for a person;
-- people already on it keep it.
CREATE TABLE retired_grade (
    id       BIGINT AUTO_INCREMENT PRIMARY KEY,
    sheet_id VARCHAR(100) NOT NULL REFERENCES sheet (id),
    grade    VARCHAR(1)   NOT NULL,
    CONSTRAINT retired_grade_unique UNIQUE (sheet_id, grade)
);
