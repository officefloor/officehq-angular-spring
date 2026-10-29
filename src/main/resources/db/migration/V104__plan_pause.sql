-- A plan's pauses: from the day it was paused to the day it was resumed (null while it is still paused). A plan date
-- on or after a pause's first day and before its resume day is skipped: no claim is made for it.
CREATE TABLE plan_pause (
    plan_id    BIGINT NOT NULL REFERENCES plan (id) ON DELETE CASCADE,
    paused_on  DATE   NOT NULL,
    resumed_on DATE
);
CREATE INDEX plan_pause_plan ON plan_pause (plan_id);
