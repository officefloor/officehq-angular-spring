-- The grades retired on a sheet, one row each: a retired grade is given to no one from then on.
CREATE TABLE sheet_grade_retired (
    id       BIGINT AUTO_INCREMENT PRIMARY KEY,
    sheet_id VARCHAR(100) NOT NULL REFERENCES sheet (id),
    grade    VARCHAR(10)  NOT NULL,
    CONSTRAINT sheet_grade_retired_unique UNIQUE (sheet_id, grade)
);
