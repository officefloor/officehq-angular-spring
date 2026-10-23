-- A plan's amount may be changed, on the sheet's today. The plan's amount is the latest one, used for the
-- claims generated from then on; claims already made keep theirs. Each change is kept, oldest first by seq.
CREATE TABLE plan_change (
    plan_id    BIGINT         NOT NULL REFERENCES plan (id),
    seq        INT            NOT NULL,
    changed_on DATE           NOT NULL,
    amount     DECIMAL(14, 2) NOT NULL,
    PRIMARY KEY (plan_id, seq)
);
