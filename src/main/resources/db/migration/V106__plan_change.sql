-- A plan's amount can be changed. Each change is kept, oldest first, with the sheet's today it was
-- made on and the new amount; the plan's amount is the latest. A claim already made keeps its own.
CREATE TABLE plan_change (
    plan_id    BIGINT         NOT NULL REFERENCES plan (id),
    position   INT            NOT NULL,
    on_date    DATE           NOT NULL,
    amount     DECIMAL(15, 2) NOT NULL,
    PRIMARY KEY (plan_id, position)
);
