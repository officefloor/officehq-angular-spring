-- Claims made by a person on a sheet, listed newest first (by id). The amount is 0.00 or more with
-- two decimals; the date defaults to the sheet's today; the note may be empty.
CREATE TABLE claim (
    id         BIGINT AUTO_INCREMENT NOT NULL PRIMARY KEY,
    sheet_id   VARCHAR(100)   NOT NULL REFERENCES sheet (id),
    person_id  BIGINT         NOT NULL REFERENCES person (id),
    amount     DECIMAL(12, 2) NOT NULL CHECK (amount >= 0),
    claim_date DATE           NOT NULL,
    note       VARCHAR(500)   NOT NULL DEFAULT ''
);
CREATE INDEX claim_sheet ON claim (sheet_id, id);
