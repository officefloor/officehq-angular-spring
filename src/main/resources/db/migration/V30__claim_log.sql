-- A claim's status log: one line per status it has had, oldest first (by seq), with who moved it, the sheet's
-- today when they did and the due it had at that moment (none when it had no figure).
CREATE TABLE claim_log (
    claim_id BIGINT       NOT NULL REFERENCES claim (id),
    seq      INT          NOT NULL,
    status   VARCHAR(20)  NOT NULL,
    by_user  VARCHAR(100) NOT NULL,
    on_date  DATE         NOT NULL,
    due      DECIMAL(14, 2),
    PRIMARY KEY (claim_id, seq)
);
