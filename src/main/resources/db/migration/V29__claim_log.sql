-- A claim's status log: one line per status it has had, oldest first (by id), with the user who
-- moved it there, the sheet's today then, and the claim's due as computed at that moment (null: n/a).
-- Claims added before the log have no lines.
CREATE TABLE claim_log (
    id         BIGINT AUTO_INCREMENT PRIMARY KEY,
    claim_id   BIGINT         NOT NULL REFERENCES claim (id),
    status     VARCHAR(16)    NOT NULL,
    by_user    VARCHAR(200)   NOT NULL,
    on_date    DATE           NOT NULL,
    due        DECIMAL(14, 2)
);
CREATE INDEX claim_log_claim ON claim_log (claim_id, id);
