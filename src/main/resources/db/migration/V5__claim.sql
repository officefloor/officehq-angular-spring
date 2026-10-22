-- A claim a person on a sheet makes: an amount (0.00 or more, two decimals), a date and a note
-- (may be empty). Listed under its person newest first (by id).
CREATE TABLE claim (
    id         BIGINT AUTO_INCREMENT PRIMARY KEY,
    sheet_id   VARCHAR(64)    NOT NULL REFERENCES sheet (id),
    person_id  BIGINT         NOT NULL REFERENCES person (id),
    amount     DECIMAL(14, 2) NOT NULL CHECK (amount >= 0),
    claim_date DATE           NOT NULL,
    note       VARCHAR(500)   NOT NULL
);
CREATE INDEX claim_sheet ON claim (sheet_id, id);
