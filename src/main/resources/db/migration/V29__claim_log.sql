-- A claim's status log: one line per status it has had, in the order it had them, with the user who made the
-- change, the sheet's today then and the claim's due as computed at that moment (null for n/a).
CREATE TABLE claim_log (
    id       BIGINT AUTO_INCREMENT PRIMARY KEY,
    claim_id BIGINT       NOT NULL REFERENCES claim (id),
    status   VARCHAR(20)  NOT NULL,
    by_user  VARCHAR(100) NOT NULL,
    on_date  DATE,
    due      DECIMAL(14, 2)
);
CREATE INDEX claim_log_claim ON claim_log (claim_id, id);
