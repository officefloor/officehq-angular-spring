-- A claim a person on a sheet makes: an amount (two decimals, 0.00 or more), a date and a note
-- (may be empty). Listed under the person newest first (id descending).
CREATE TABLE claim (
    id BIGINT AUTO_INCREMENT PRIMARY KEY,
    sheet_id VARCHAR(200) NOT NULL REFERENCES sheet (id),
    person_id BIGINT NOT NULL REFERENCES person (id),
    amount DECIMAL(12, 2) NOT NULL CHECK (amount >= 0),
    claim_date DATE NOT NULL,
    note VARCHAR(2000) NOT NULL DEFAULT ''
);
CREATE INDEX claim_sheet ON claim (sheet_id, id);
CREATE INDEX claim_person ON claim (person_id, id);
