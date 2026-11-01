-- The grades retired on a sheet: a retired grade cannot be given to a new person nor chosen in an edit; people
-- already on it keep it. Not dated.
CREATE TABLE sheet_grade_retired (
    sheet_id VARCHAR(100) NOT NULL REFERENCES sheet (id),
    grade    VARCHAR(1)   NOT NULL,
    PRIMARY KEY (sheet_id, grade)
);
