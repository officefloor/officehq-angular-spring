-- A claim's status log: one line per status it has had, oldest first, with who made the change, the sheet's
-- today then, and the claim's due as it was computed at that moment (n/a when it had none). A claim already there
-- has no log.
CREATE TABLE claim_status_log (
    claim_id  BIGINT       NOT NULL REFERENCES claim (id),
    seq       INT          NOT NULL,
    status    VARCHAR(20)  NOT NULL,
    by_user   VARCHAR(255) NOT NULL,
    on_date   DATE         NOT NULL,
    due       VARCHAR(20)  NOT NULL,
    PRIMARY KEY (claim_id, seq)
);
