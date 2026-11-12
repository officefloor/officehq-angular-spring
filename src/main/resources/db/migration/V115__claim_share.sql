-- The people a claim is shared with, in the order they were added: each holds a share of the claim's due (a percent
-- with two decimals, more than 0.00); a claim's shares add up to at most 100.00, and its own person holds none.
CREATE TABLE claim_share (
    id        BIGINT AUTO_INCREMENT PRIMARY KEY,
    sheet_id  VARCHAR(100) NOT NULL REFERENCES sheet (id),
    claim_id  BIGINT       NOT NULL REFERENCES claim (id),
    person_id BIGINT       NOT NULL REFERENCES person (id),
    share     DECIMAL(5, 2) NOT NULL CHECK (share > 0 AND share <= 100),
    UNIQUE (claim_id, person_id)
);
CREATE INDEX claim_share_sheet ON claim_share (sheet_id, id);
