-- A person's claims: an amount (0.00 or more, two decimals), a date and a note, listed newest
-- first (by id).
CREATE TABLE claim (
    id         BIGINT AUTO_INCREMENT PRIMARY KEY,
    sheet_id   VARCHAR(100)   NOT NULL REFERENCES sheet (id),
    person_id  BIGINT         NOT NULL REFERENCES person (id),
    amount     DECIMAL(14, 2) NOT NULL CHECK (amount >= 0),
    claim_date DATE           NOT NULL,
    note       VARCHAR(500)   NOT NULL
);
CREATE INDEX claim_person ON claim (sheet_id, person_id, id);
