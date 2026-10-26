-- The grades retired on a sheet, each at most once. A retired grade can no longer be given to anyone;
-- the people on it keep it.
CREATE TABLE retired_grade (
    id       BIGINT AUTO_INCREMENT PRIMARY KEY,
    sheet_id VARCHAR(100) NOT NULL REFERENCES sheet (id),
    grade    VARCHAR(1)   NOT NULL
);
CREATE UNIQUE INDEX retired_grade_once ON retired_grade (sheet_id, grade);
