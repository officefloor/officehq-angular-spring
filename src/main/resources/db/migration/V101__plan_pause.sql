-- A plan's pauses: paused on a day and resumed on a later day (null: still paused). A plan date on or after the
-- day it was paused and before the day it was resumed is skipped.
CREATE TABLE sheet_plan_pause (
    id         BIGINT AUTO_INCREMENT PRIMARY KEY,
    plan_id    BIGINT NOT NULL REFERENCES sheet_plan (id),
    paused_on  DATE NOT NULL,
    resumed_on DATE
);
