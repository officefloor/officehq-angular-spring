-- The people a claim is shared with: each holder (a person of the claim's sheet other than its own) and their share
-- (two decimals, more than 0.00; a claim's shares total at most 100.00), in the order they were added.
CREATE TABLE claim_share (
    id        BIGINT AUTO_INCREMENT PRIMARY KEY,
    claim_id  BIGINT        NOT NULL REFERENCES claim (id),
    person_id BIGINT        NOT NULL,
    share     DECIMAL(5, 2) NOT NULL
);
CREATE INDEX claim_share_claim ON claim_share (claim_id, id);
