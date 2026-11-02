-- Each claim's shares, in the order they were added: another person of its sheet holding a share
-- percent (two decimals, more than 0.00) of its due; a claim's shares add up to at most 100.00.
CREATE TABLE claim_share (
    claim_id  BIGINT        NOT NULL REFERENCES claim (id),
    seq       INT           NOT NULL,
    person_id BIGINT        NOT NULL,
    share     DECIMAL(5, 2) NOT NULL,
    PRIMARY KEY (claim_id, seq)
);
