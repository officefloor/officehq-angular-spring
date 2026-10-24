-- Claims a person makes, listed under the person newest first (their id). The amount is 0.00 or
-- more with up to two decimals; the date defaults to the sheet's today; the note may be empty.
CREATE TABLE claim (
    id         BIGINT AUTO_INCREMENT PRIMARY KEY,
    sheet_id   VARCHAR(100)   NOT NULL REFERENCES sheet (id),
    person_id  BIGINT         NOT NULL REFERENCES person (id),
    amount     DECIMAL(12, 2) NOT NULL CHECK (amount >= 0),
    claim_date DATE           NOT NULL,
    note       VARCHAR(500)   NOT NULL
);
CREATE INDEX claim_sheet ON claim (sheet_id, id);
CREATE INDEX claim_person ON claim (person_id, id);
