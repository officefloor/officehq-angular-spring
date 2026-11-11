-- A plan may be paused and resumed. Each pause is kept: the day it was paused and the day it was
-- resumed (null while it is still paused). A plan date on or after the paused day and before the
-- resumed day is skipped: generating makes nothing for it and it is not among the next dates.
CREATE TABLE plan_pause (
    plan_id    BIGINT NOT NULL REFERENCES plan (id),
    paused_on  DATE NOT NULL,
    resumed_on DATE,
    CONSTRAINT plan_pause_dates CHECK (resumed_on IS NULL OR resumed_on >= paused_on)
);
