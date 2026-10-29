-- A plan's changes of amount, in the order they were made: the sheet's today when made and the new amount. The plan's
-- amount is the last one; it applies to the claims generated from then on, those already made keeping theirs.
CREATE TABLE plan_change (
    plan_id    BIGINT         NOT NULL REFERENCES plan (id) ON DELETE CASCADE,
    pos        INT            NOT NULL,
    changed_on DATE           NOT NULL,
    amount     DECIMAL(14, 2) NOT NULL CHECK (amount >= 0),
    PRIMARY KEY (plan_id, pos)
);
