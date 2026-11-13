-- A plan may be paused and resumed: each pause keeps the day it was paused and the day it was resumed (none
-- while it is still paused). A plan date on or after a pause's day and before its resume day is skipped.
CREATE TABLE plan_pause (
    plan_id    BIGINT NOT NULL REFERENCES plan (id),
    paused_on  DATE   NOT NULL,
    resumed_on DATE
);
