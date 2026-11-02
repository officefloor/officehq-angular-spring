-- A claim's status log: one line per status it has had, oldest first (by id). Each line keeps who
-- made the change, the sheet's today then, and the claim's due as computed at that moment (null
-- when it had none).
CREATE TABLE claim_log (
    id        BIGINT AUTO_INCREMENT PRIMARY KEY,
    sheet_id  VARCHAR(100)   NOT NULL REFERENCES sheet (id),
    claim_id  BIGINT         NOT NULL REFERENCES claim (id),
    status    VARCHAR(20)    NOT NULL,
    by_user   VARCHAR(100)   NOT NULL,
    on_date   DATE           NOT NULL,
    due       DECIMAL(14, 2)
);
CREATE INDEX claim_log_sheet ON claim_log (sheet_id, id);
