-- A plan's amount changes, in the order they were made: the day (the sheet's today) and the new amount. The plan's
-- amount is the latest; claims already generated keep theirs.
CREATE TABLE sheet_plan_change (
    id         BIGINT AUTO_INCREMENT PRIMARY KEY,
    plan_id    BIGINT NOT NULL REFERENCES sheet_plan (id),
    changed_on DATE NOT NULL,
    amount     DECIMAL(14, 2) NOT NULL
);
