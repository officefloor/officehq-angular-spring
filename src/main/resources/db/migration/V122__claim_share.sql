-- The shares of a claim held by other people of its sheet, in the order they were given (by id).
-- Each names the person holding it and their share percent (two decimals, above 0.00); a claim's
-- shares add up to 100.00 at most, and no person holds two shares of one claim. A share whose
-- person is gone from the sheet (merged or transferred away) counts nowhere.
CREATE TABLE claim_share (
    id        BIGINT AUTO_INCREMENT PRIMARY KEY,
    sheet_id  VARCHAR(100)  NOT NULL REFERENCES sheet (id),
    claim_id  BIGINT        NOT NULL REFERENCES claim (id),
    person_id BIGINT        NOT NULL,
    share     DECIMAL(5, 2) NOT NULL
);
CREATE INDEX claim_share_claim ON claim_share (claim_id, id);
