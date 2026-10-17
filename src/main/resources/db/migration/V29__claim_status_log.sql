-- A claim's status log: one line per status it has had, oldest first (by id), with who made the
-- change, the sheet's today then and the claim's due as computed at that moment (null for no rate).
-- Claims already kept start with no lines.
CREATE TABLE claim_status_log (
    id         BIGINT AUTO_INCREMENT NOT NULL PRIMARY KEY,
    sheet_id   VARCHAR(100)   NOT NULL REFERENCES sheet (id),
    claim_id   BIGINT         NOT NULL REFERENCES claim (id),
    status     VARCHAR(10)    NOT NULL CHECK (status IN ('DRAFT', 'OPEN', 'PENDING', 'SETTLED', 'VOID')),
    by_user    VARCHAR(100)   NOT NULL,
    on_date    DATE           NOT NULL,
    due        DECIMAL(14, 2)
);
CREATE INDEX claim_status_log_sheet ON claim_status_log (sheet_id, id);
