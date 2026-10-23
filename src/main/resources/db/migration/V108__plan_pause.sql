-- A plan may be paused and resumed, each on the sheet's today. A plan date on or after the day a pause
-- began and before the day it ended is skipped, not postponed; a pause not yet resumed has no resumed_on.
CREATE TABLE plan_pause (
    plan_id    BIGINT NOT NULL REFERENCES plan (id),
    paused_on  DATE   NOT NULL,
    resumed_on DATE
);
CREATE INDEX plan_pause_plan ON plan_pause (plan_id);
