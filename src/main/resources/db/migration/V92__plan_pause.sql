-- A plan may be paused and resumed, each on the sheet's today then. A plan date on or after the day it
-- was paused and before the day it was resumed is skipped, not postponed: generating never makes its
-- claim. Each pause is one row; resumed_on is null while the plan is still paused.
CREATE TABLE person_plan_pause (
    plan_id    BIGINT NOT NULL REFERENCES person_plan (id) ON DELETE CASCADE,
    paused_on  DATE   NOT NULL,
    resumed_on DATE
);
CREATE INDEX person_plan_pause_plan ON person_plan_pause (plan_id);
