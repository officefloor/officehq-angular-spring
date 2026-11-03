-- A claim's status log: one line per status it has had, oldest first, with who made the change, the
-- sheet's today then and the claim's due as computed at that moment ('n/a' when it had none).
CREATE TABLE claim_log (
    claim_id BIGINT      NOT NULL REFERENCES claim (id),
    seq      INTEGER     NOT NULL,
    status   VARCHAR(10) NOT NULL CHECK (status IN ('DRAFT', 'OPEN', 'PENDING', 'SETTLED', 'VOID')),
    by_user  VARCHAR(100) NOT NULL,
    log_date DATE        NOT NULL,
    due      VARCHAR(20) NOT NULL,
    PRIMARY KEY (claim_id, seq)
);
