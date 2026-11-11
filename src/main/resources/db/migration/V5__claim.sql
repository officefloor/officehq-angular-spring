-- A person's claims, each with an amount (0.00 or more, two decimals), a date and a note that may
-- be empty. Listed newest first, which is the reverse order of their ids.
CREATE TABLE claim (
    id         BIGINT AUTO_INCREMENT PRIMARY KEY,
    sheet_id   VARCHAR(100)   NOT NULL REFERENCES sheet (id),
    person_id  BIGINT         NOT NULL REFERENCES person (id),
    amount     DECIMAL(12, 2) NOT NULL CHECK (amount >= 0),
    claim_date DATE           NOT NULL,
    note       VARCHAR(1000)  NOT NULL
);
CREATE INDEX claim_sheet ON claim (sheet_id, id);
CREATE INDEX claim_person ON claim (person_id, id);
