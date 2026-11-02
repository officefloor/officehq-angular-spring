-- A plan's pauses: from the day it was paused up to the day it was resumed (none while it is still
-- paused). A plan date on or after a pause's from-day and before its until-day is skipped.
CREATE TABLE claim_plan_pause (
    id       BIGINT AUTO_INCREMENT PRIMARY KEY,
    sheet_id VARCHAR(100) NOT NULL REFERENCES sheet (id),
    plan_id  BIGINT       NOT NULL REFERENCES claim_plan (id),
    from_on  DATE         NOT NULL,
    until_on DATE
);
CREATE INDEX claim_plan_pause_sheet ON claim_plan_pause (sheet_id, plan_id);
