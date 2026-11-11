-- A claim's status log: one line per status it has had, in the order of their ids. Each keeps who made the change,
-- the sheet's today then, and the claim's due as it was computed at that moment (two decimals, or n/a).
CREATE TABLE claim_log (
    id        BIGINT AUTO_INCREMENT PRIMARY KEY,
    sheet_id  VARCHAR(100) NOT NULL REFERENCES sheet (id),
    claim_id  BIGINT       NOT NULL REFERENCES claim (id),
    status    VARCHAR(10)  NOT NULL,
    by_user   VARCHAR(100) NOT NULL,
    logged_on DATE         NOT NULL,
    due       VARCHAR(20)  NOT NULL
);
CREATE INDEX claim_log_sheet ON claim_log (sheet_id, id);
