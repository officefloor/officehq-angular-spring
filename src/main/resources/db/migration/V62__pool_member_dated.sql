-- A pool membership may carry a from-date and a to-date (either may be absent: from the beginning of
-- time, or without end). A member whose dates do not include the sheet's today is left out of the pool.
ALTER TABLE pool_member ADD COLUMN from_date DATE;
ALTER TABLE pool_member ADD COLUMN to_date DATE;
ALTER TABLE pool_member ADD CONSTRAINT pool_member_dates
    CHECK (from_date IS NULL OR to_date IS NULL OR to_date >= from_date);
