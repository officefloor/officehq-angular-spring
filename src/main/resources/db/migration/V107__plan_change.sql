-- The changes of a plan's amount, in the order made: the sheet's today when it was changed and the new amount. The
-- new amount is the plan's from then on; claims already generated keep theirs.
CREATE TABLE plan_change (
    id         BIGINT AUTO_INCREMENT PRIMARY KEY,
    plan_id    BIGINT         NOT NULL REFERENCES plan (id),
    changed_on DATE           NOT NULL,
    amount     DECIMAL(14, 2) NOT NULL
);
CREATE INDEX plan_change_plan ON plan_change (plan_id);
