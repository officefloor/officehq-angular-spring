-- The people a claim is shared with, in the order they were added: the holder (another person on the
-- sheet) and their share percent of the claim's due (two decimals, more than 0.00; all of a claim's
-- shares together at most 100.00).
CREATE TABLE claim_share (
    claim_id  BIGINT        NOT NULL REFERENCES claim (id),
    seq       INTEGER       NOT NULL,
    holder_id BIGINT        NOT NULL,
    share_pct DECIMAL(5, 2) NOT NULL,
    PRIMARY KEY (claim_id, seq)
);
