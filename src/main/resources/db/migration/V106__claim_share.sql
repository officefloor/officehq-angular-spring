-- The other people on the sheet a claim is shared with, in the order they were added: each holds a share
-- of the claim's due, a percent with two decimals (more than 0.00; a claim's shares total at most 100.00).
-- A claim already there is shared with no one.
CREATE TABLE claim_share (
    claim_id BIGINT NOT NULL REFERENCES claim(id),
    seq INT NOT NULL,
    person_id BIGINT NOT NULL,
    share NUMERIC(5, 2) NOT NULL,
    PRIMARY KEY (claim_id, seq)
);
