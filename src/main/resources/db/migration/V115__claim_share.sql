-- Each claim's shares: a person on the sheet holding a part of its due, as a percent with two decimals, in
-- the order shared. Existing claims start with none.
CREATE TABLE claim_share (
    claim_id  BIGINT        NOT NULL REFERENCES claim (id),
    seq       INT           NOT NULL,
    person_id BIGINT        NOT NULL,
    share_pct DECIMAL(5, 2) NOT NULL,
    PRIMARY KEY (claim_id, seq)
);
