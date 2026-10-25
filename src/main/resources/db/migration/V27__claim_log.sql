-- A claim's status log: one line per status it has had, in order (position 0 the oldest), each with
-- who made the change, the sheet's today then, and the claim's due as worked out at that moment (null
-- when n/a). A claim already stored has no log of its past.
CREATE TABLE claim_log (
    claim_id BIGINT         NOT NULL REFERENCES claim (id),
    position INT            NOT NULL,
    status   VARCHAR(20)    NOT NULL,
    by_user  VARCHAR(200)   NOT NULL,
    on_date  DATE           NOT NULL,
    due      DECIMAL(12, 2),
    PRIMARY KEY (claim_id, position)
);
