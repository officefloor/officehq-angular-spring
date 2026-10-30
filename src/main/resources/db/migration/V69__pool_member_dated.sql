-- A pool membership may be dated: from (blank: from the start) to (blank: no end), both days included, the to-date
-- not before the from-date. A member whose dates do not include the sheet's today is left out of its pool entirely.
ALTER TABLE pool_member ADD COLUMN from_date DATE;
ALTER TABLE pool_member ADD COLUMN to_date DATE;
ALTER TABLE pool_member ADD CONSTRAINT pool_member_dates CHECK (from_date IS NULL OR to_date IS NULL OR to_date >= from_date);
