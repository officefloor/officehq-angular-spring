-- A plan's amount changes: the day it was changed (the sheet's today) and its new amount, which the
-- claims generated from then on take. The plan's own amount is kept as the latest.
CREATE TABLE claim_plan_change (
    id         BIGINT AUTO_INCREMENT PRIMARY KEY,
    sheet_id   VARCHAR(100)   NOT NULL REFERENCES sheet (id),
    plan_id    BIGINT         NOT NULL REFERENCES claim_plan (id),
    changed_on DATE           NOT NULL,
    amount     DECIMAL(14, 2) NOT NULL
);
CREATE INDEX claim_plan_change_sheet ON claim_plan_change (sheet_id, plan_id);
