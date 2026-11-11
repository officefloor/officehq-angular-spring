-- A plan's amount may be changed. Each change is kept, in the order made: the sheet's today it was
-- made on and the new amount. The plan's amount is the latest; claims already generated keep theirs.
CREATE TABLE plan_change (
    plan_id    BIGINT NOT NULL REFERENCES plan (id),
    seq        INT NOT NULL,
    changed_on DATE NOT NULL,
    amount     NUMERIC(14, 2) NOT NULL,
    PRIMARY KEY (plan_id, seq)
);
