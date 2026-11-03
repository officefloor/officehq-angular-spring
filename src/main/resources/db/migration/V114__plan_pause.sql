-- The times a plan was paused: from the day it was paused up to (not including) the day it was
-- resumed, null while it is still paused. A plan date in a pause is skipped, never made.
CREATE TABLE plan_pause (
    plan_id    BIGINT NOT NULL REFERENCES plan (id) ON DELETE CASCADE,
    paused_on  DATE   NOT NULL,
    resumed_on DATE
);
CREATE INDEX plan_pause_plan ON plan_pause (plan_id);
