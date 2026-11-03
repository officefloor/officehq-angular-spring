-- The changes of a plan's amount, oldest first: the sheet's today it was changed on and the new
-- amount, which the claims it makes from then on take.
CREATE TABLE plan_change (
    plan_id    BIGINT         NOT NULL REFERENCES plan (id) ON DELETE CASCADE,
    position   INT            NOT NULL,
    changed_on DATE           NOT NULL,
    amount     DECIMAL(14, 2) NOT NULL,
    CONSTRAINT plan_change_position UNIQUE (plan_id, position)
);
