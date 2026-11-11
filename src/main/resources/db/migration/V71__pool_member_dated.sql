-- A pool membership may run from a from-date to a to-date (both inclusive, both optional, the to-date never before the
-- from-date); a member whose dates leave out the sheet's today counts for nothing in the pool.
ALTER TABLE pool_member ADD COLUMN from_date DATE;
ALTER TABLE pool_member ADD COLUMN to_date DATE;
ALTER TABLE pool_member ADD CONSTRAINT pool_member_dates CHECK (from_date IS NULL OR to_date IS NULL OR to_date >= from_date);
