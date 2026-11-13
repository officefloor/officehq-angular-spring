-- A plan's amount may be changed: each change keeps the day it was made (the sheet's today) and the new
-- amount, in the order made. The plan's amount is the latest one; claims already made keep theirs.
CREATE TABLE plan_change (
    plan_id    BIGINT        NOT NULL REFERENCES plan (id),
    seq        INT           NOT NULL,
    changed_on DATE          NOT NULL,
    amount     NUMERIC(15,2) NOT NULL,
    PRIMARY KEY (plan_id, seq)
);
