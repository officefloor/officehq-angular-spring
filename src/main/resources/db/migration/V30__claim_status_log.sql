-- Each status a claim has had, oldest first: the status, who moved it there, the sheet's today when
-- they did, and the claim's due as it was then ('n/a' when it had none). Claims added before the log
-- existed have no lines.
CREATE TABLE claim_status_log (
    id BIGINT AUTO_INCREMENT PRIMARY KEY,
    sheet_id VARCHAR(200) NOT NULL REFERENCES sheet (id),
    claim_id BIGINT NOT NULL REFERENCES claim (id),
    status VARCHAR(20) NOT NULL,
    changed_by VARCHAR(255) NOT NULL,
    changed_on DATE NOT NULL,
    due VARCHAR(32) NOT NULL
);
CREATE INDEX claim_status_log_sheet ON claim_status_log (sheet_id, id);
CREATE INDEX claim_status_log_claim ON claim_status_log (claim_id, id);
