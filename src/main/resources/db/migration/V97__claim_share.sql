-- A claim's shares, in the order they were given (position 0 the first): each a person on the sheet
-- holding a part of the claim's due, as a percent with two decimals.
CREATE TABLE claim_share (
    claim_id  BIGINT        NOT NULL REFERENCES claim (id),
    position  INT           NOT NULL,
    person_id BIGINT        NOT NULL,
    share     DECIMAL(5, 2) NOT NULL,
    PRIMARY KEY (claim_id, position)
);
