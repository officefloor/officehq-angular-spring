-- A sheet's retired grades: a retired grade cannot be chosen for a person any more (the people on it
-- keep it). A grade is retired on a sheet at most once.
CREATE TABLE retired_grade (
    id BIGINT AUTO_INCREMENT PRIMARY KEY,
    sheet_id VARCHAR(200) NOT NULL REFERENCES sheet (id),
    grade VARCHAR(1) NOT NULL
);
CREATE UNIQUE INDEX retired_grade_sheet_grade ON retired_grade (sheet_id, grade);
