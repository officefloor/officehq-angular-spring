-- A claim's status log: one line per status it has had, oldest first (by id), each with the user
-- who made the change, the sheet's today then and the claim's due as computed at that moment
-- (null: n/a). Claims added before this change have no lines.
CREATE TABLE claim_log (
    id        BIGINT AUTO_INCREMENT PRIMARY KEY,
    sheet_id  VARCHAR(100)   NOT NULL REFERENCES sheet (id),
    claim_id  BIGINT         NOT NULL REFERENCES claim (id),
    status    VARCHAR(10)    NOT NULL CHECK (status IN ('DRAFT', 'OPEN', 'PENDING', 'SETTLED', 'VOID')),
    by_user   VARCHAR(100)   NOT NULL,
    on_date   DATE           NOT NULL,
    due       DECIMAL(15, 2)
);
CREATE INDEX claim_log_sheet ON claim_log (sheet_id, id);
CREATE INDEX claim_log_claim ON claim_log (claim_id, id);
