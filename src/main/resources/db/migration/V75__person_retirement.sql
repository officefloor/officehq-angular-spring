-- A person who is unretired keeps the retirement they had: from the date they were retired from to
-- the sheet's today when they were unretired. A person may have several over time.
CREATE TABLE person_retirement (
    id BIGINT AUTO_INCREMENT PRIMARY KEY,
    sheet_id VARCHAR(200) NOT NULL REFERENCES sheet (id),
    person_id BIGINT NOT NULL REFERENCES person (id) ON DELETE CASCADE,
    from_date DATE NOT NULL,
    to_date DATE NOT NULL
);
CREATE INDEX person_retirement_sheet ON person_retirement (sheet_id, person_id);
