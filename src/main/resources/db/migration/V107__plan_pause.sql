-- The times a plan was paused: from the day it was paused up to (not including) the day it was resumed, or with no
-- end while it is still paused. A plan date in such a time is skipped: generate makes nothing for it and it is not
-- among the plan's next dates. A plan has at most one pause still open.
CREATE TABLE plan_pause (
    id          BIGINT AUTO_INCREMENT PRIMARY KEY,
    sheet_id    VARCHAR(100) NOT NULL REFERENCES sheet (id),
    plan_id     BIGINT       NOT NULL REFERENCES plan (id) ON DELETE CASCADE,
    paused_on   DATE         NOT NULL,
    resumed_on  DATE
);
CREATE INDEX plan_pause_sheet ON plan_pause (sheet_id, plan_id);
