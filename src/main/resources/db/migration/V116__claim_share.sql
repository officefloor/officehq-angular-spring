-- The people a claim is shared with, in the order they were added (by id): each another person on the
-- sheet, at most once per claim, with their share percent (two decimals, above 0.00; a claim's shares
-- total at most 100.00). Each holder's due-total counts their part of the claim's due; its own
-- person's counts the rest.
CREATE TABLE claim_share (
    id         BIGINT AUTO_INCREMENT PRIMARY KEY,
    claim_id   BIGINT        NOT NULL REFERENCES claim (id),
    person_id  BIGINT        NOT NULL,
    share      DECIMAL(5, 2) NOT NULL
);
CREATE INDEX claim_share_claim ON claim_share (claim_id, id);
