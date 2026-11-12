-- A pool membership may run from a date and to a date (both optional, inclusive; the to-date not before the
-- from-date). A member whose dates do not include the sheet's today is left out of its pool entirely.
ALTER TABLE pool_member ADD COLUMN from_date DATE;
ALTER TABLE pool_member ADD COLUMN to_date DATE;
