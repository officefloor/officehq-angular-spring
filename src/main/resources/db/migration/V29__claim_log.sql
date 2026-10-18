-- A claim's status log: one entry per status it has had, in order (seq), each with the user who made
-- the change, the sheet's today then and the claim's due as it was worked out then (null: n/a).
CREATE TABLE claim_log (
    claim_id  BIGINT        NOT NULL REFERENCES claim (id),
    seq       INT           NOT NULL,
    status    VARCHAR(10)   NOT NULL,
    by_user   VARCHAR(200)  NOT NULL,
    logged_on DATE          NOT NULL,
    due       DECIMAL(15, 2),
    PRIMARY KEY (claim_id, seq)
);
