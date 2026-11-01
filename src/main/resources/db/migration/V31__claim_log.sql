-- Each claim's status log, oldest first: one line per status it has had (DRAFT when added), who
-- gave it, the sheet's today then and the claim's due as it was computed at that moment (empty
-- when it had none). Claims added before the log was kept start with no lines.
CREATE TABLE claim_log (
    claim_id  BIGINT       NOT NULL REFERENCES claim (id),
    seq       INT          NOT NULL,
    status    VARCHAR(20)  NOT NULL,
    by_user   VARCHAR(100) NOT NULL,
    log_date  DATE         NOT NULL,
    due       DECIMAL(14, 2),
    PRIMARY KEY (claim_id, seq)
);
