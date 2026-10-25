-- The changes of a plan's amount, oldest first: the sheet's today it was changed on and the new amount.
-- The plan's own amount is the latest; claims generated before a change keep the amount they were made with.
CREATE TABLE plan_change (
    plan_id    BIGINT         NOT NULL REFERENCES person_plan (id) ON DELETE CASCADE,
    position   INT            NOT NULL,
    changed_on DATE           NOT NULL,
    amount     DECIMAL(12, 2) NOT NULL,
    PRIMARY KEY (plan_id, position)
);
