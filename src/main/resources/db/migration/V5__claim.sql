-- A claim a person makes: an amount (0.00 or more, two decimals), a date and a note (may be
-- empty). Listed under its person newest first (id).
CREATE TABLE claim (
    id         BIGINT AUTO_INCREMENT PRIMARY KEY,
    sheet_id   VARCHAR(100)   NOT NULL REFERENCES sheet (id),
    person_id  BIGINT         NOT NULL REFERENCES person (id),
    amount     DECIMAL(15, 2) NOT NULL CHECK (amount >= 0),
    claim_date DATE           NOT NULL,
    note       VARCHAR(1000)  NOT NULL
);
CREATE INDEX claim_sheet ON claim (sheet_id, id);
