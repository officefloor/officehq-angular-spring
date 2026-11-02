-- A pool membership may carry a from-date and a to-date (both optional, inclusive): a member whose
-- dates do not include the sheet's today is left out of the pool.
ALTER TABLE pool_member ADD COLUMN from_date DATE;
ALTER TABLE pool_member ADD COLUMN to_date DATE;
