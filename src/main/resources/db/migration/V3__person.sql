-- People on a sheet, listed in the order they were added (their id).
CREATE TABLE person (
    id       BIGINT AUTO_INCREMENT PRIMARY KEY,
    sheet_id VARCHAR(100) NOT NULL REFERENCES sheet (id),
    name     VARCHAR(200) NOT NULL,
    grade    VARCHAR(1)   NOT NULL
);
CREATE INDEX person_sheet ON person (sheet_id, id);
