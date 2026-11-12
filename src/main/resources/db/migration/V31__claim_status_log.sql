-- Each claim's status log: one line per status it has had, oldest first, with who made the change, the
-- sheet's today then, and the claim's due as it was computed at that moment (none when it was n/a).
-- Existing claims start with no lines.
CREATE TABLE claim_status_log (
    claim_id BIGINT       NOT NULL REFERENCES claim (id),
    seq      INT          NOT NULL,
    status   VARCHAR(20)  NOT NULL,
    by_user  VARCHAR(200) NOT NULL,
    on_date  DATE         NOT NULL,
    due      DECIMAL(19, 2),
    PRIMARY KEY (claim_id, seq)
);
