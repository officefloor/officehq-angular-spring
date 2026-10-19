-- Claims a person makes on a sheet: an amount (0.00 or more, two decimals), a date and a note.
-- A person's claims are listed newest first (by id).
CREATE TABLE claim (
    id         BIGINT AUTO_INCREMENT PRIMARY KEY,
    sheet_id   VARCHAR(100)   NOT NULL REFERENCES sheet (id),
    person_id  BIGINT         NOT NULL REFERENCES person (id),
    amount     DECIMAL(12, 2) NOT NULL CHECK (amount >= 0),
    claim_date DATE           NOT NULL,
    note       VARCHAR(1000)  NOT NULL
);
CREATE INDEX claim_sheet ON claim (sheet_id, person_id, id);
