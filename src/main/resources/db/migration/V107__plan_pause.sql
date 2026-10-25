-- The times a plan was paused: the day it was paused and the day it was resumed (null while it is still
-- paused). A plan date on or after the day it was paused and before the day it was resumed is skipped:
-- generating makes no claim for it, then or later.
CREATE TABLE plan_pause (
    plan_id    BIGINT NOT NULL REFERENCES person_plan (id) ON DELETE CASCADE,
    paused_on  DATE   NOT NULL,
    resumed_on DATE
);
CREATE INDEX plan_pause_plan ON plan_pause (plan_id);
