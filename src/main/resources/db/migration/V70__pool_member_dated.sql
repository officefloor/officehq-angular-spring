-- A pool membership may carry a from-date and a to-date (both included; null: no bound). A member whose dates do not
-- include the sheet's today is left out of the pool: not in its total share, not in its balance, no cut.
ALTER TABLE pool_member ADD COLUMN from_date DATE;
ALTER TABLE pool_member ADD COLUMN to_date DATE;
ALTER TABLE pool_member ADD CONSTRAINT pool_member_dates
    CHECK (from_date IS NULL OR to_date IS NULL OR to_date >= from_date);
