-- A claim's shares, one row each, in the order they were given by id: another person on the sheet who
-- holds a part of what is due on the claim, as a percent (two decimals, more than 0.00); a claim's
-- shares total at most 100.00, and a person holds at most one share of a claim.
CREATE TABLE claim_share (
    id        BIGINT AUTO_INCREMENT PRIMARY KEY,
    sheet_id  VARCHAR(100)   NOT NULL,
    claim_id  BIGINT         NOT NULL,
    person_id BIGINT         NOT NULL,
    share     DECIMAL(5, 2)  NOT NULL,
    UNIQUE (claim_id, person_id)
);
CREATE INDEX claim_share_sheet ON claim_share (sheet_id);
