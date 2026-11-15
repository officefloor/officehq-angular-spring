-- The people a claim is shared with, in the order they were added: each holds a share (a percent
-- with two decimals, more than 0.00) of the claim's due. The claim's own person is never a holder,
-- a person holds at most one share of a claim, and a claim's shares total at most 100.00; the
-- service enforces that.
CREATE TABLE claim_share (
    claim_id  BIGINT        NOT NULL REFERENCES claim (id),
    position  INT           NOT NULL,
    person_id BIGINT        NOT NULL,
    share     NUMERIC(5, 2) NOT NULL,
    PRIMARY KEY (claim_id, position)
);
