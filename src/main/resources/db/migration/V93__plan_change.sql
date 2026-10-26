-- A plan's amount may be changed, on the sheet's today then. The new amount applies to the claims
-- generated from then on; claims already generated keep theirs. Each change is one row, kept in the
-- order they were made (position), with the day it was made and the new amount.
CREATE TABLE person_plan_change (
    plan_id    BIGINT         NOT NULL REFERENCES person_plan (id) ON DELETE CASCADE,
    position   INT            NOT NULL,
    changed_on DATE           NOT NULL,
    amount     DECIMAL(12, 2) NOT NULL,
    PRIMARY KEY (plan_id, position)
);
