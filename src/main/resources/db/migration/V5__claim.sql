-- A person's claims: an amount (0.00 or more, two decimals), a date and a note (may be empty).
-- Listed newest first (by id, descending).
CREATE TABLE claim (
    id         BIGINT AUTO_INCREMENT PRIMARY KEY,
    sheet_id   VARCHAR(100)  NOT NULL REFERENCES sheet (id),
    person_id  BIGINT        NOT NULL REFERENCES person (id),
    amount     DECIMAL(14, 2) NOT NULL CHECK (amount >= 0),
    claim_date DATE          NOT NULL,
    note       VARCHAR(500)  NOT NULL DEFAULT ''
);
CREATE INDEX claim_sheet ON claim (sheet_id, id);
CREATE INDEX claim_person ON claim (person_id, id);
