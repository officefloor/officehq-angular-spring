-- A plan can be paused and resumed. paused_on is the sheet's today it was paused on, null while it
-- runs; each past pause is kept, from the day it was paused to (not including) the day it was
-- resumed. A plan date on or after a pause's start and before its end is skipped, not postponed;
-- while paused, every date from paused_on is.
ALTER TABLE plan ADD COLUMN paused_on DATE;

CREATE TABLE plan_pause (
    plan_id    BIGINT NOT NULL REFERENCES plan (id),
    position   INT    NOT NULL,
    from_date  DATE   NOT NULL,
    to_date    DATE   NOT NULL,
    PRIMARY KEY (plan_id, position)
);
