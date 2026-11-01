-- The times a plan was paused: from paused_on up to (not including) resumed_on, empty while it is still paused. A plan
-- date in a pause is skipped: generating makes nothing for it and it is left out of the plan's next dates.
CREATE TABLE plan_pause (
    id         BIGINT AUTO_INCREMENT PRIMARY KEY,
    plan_id    BIGINT NOT NULL REFERENCES plan (id),
    paused_on  DATE   NOT NULL,
    resumed_on DATE
);
CREATE INDEX plan_pause_plan ON plan_pause (plan_id);
