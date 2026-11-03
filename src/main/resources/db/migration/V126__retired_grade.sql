-- The grades retired on a sheet, each at most once. A retired grade cannot be chosen for a person
-- (added, edited, quick-added or imported); people already on it keep it. Not dated.
CREATE TABLE retired_grade (
    id         BIGINT AUTO_INCREMENT PRIMARY KEY,
    sheet_id   VARCHAR(100) NOT NULL REFERENCES sheet (id),
    grade      VARCHAR(1)   NOT NULL CHECK (grade IN ('A', 'B', 'C')),
    retired_on DATE         NOT NULL,
    CONSTRAINT retired_grade_unique UNIQUE (sheet_id, grade)
);
