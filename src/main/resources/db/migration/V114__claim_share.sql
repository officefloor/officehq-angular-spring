-- A claim's shares, in the order given (by seq): each names another person on the sheet holding a part of the
-- claim's due and their share percent (two decimals, more than 0.00; a claim's shares add up to at most 100.00).
CREATE TABLE claim_share (
    claim_id  BIGINT        NOT NULL REFERENCES claim (id),
    seq       INT           NOT NULL,
    person_id BIGINT        NOT NULL,
    share     DECIMAL(5, 2) NOT NULL,
    PRIMARY KEY (claim_id, seq)
);
