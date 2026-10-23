-- A grade retired on a sheet: who retired it. People on it keep it, but it is given to nobody else.
CREATE TABLE grade_retired (
    sheet_id   VARCHAR(64)  NOT NULL REFERENCES sheet (id),
    grade      VARCHAR(1)   NOT NULL,
    retired_by VARCHAR(200) NOT NULL,
    PRIMARY KEY (sheet_id, grade)
);
