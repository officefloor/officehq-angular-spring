-- A pool membership may carry a from-date and a to-date, both included (null: no bound). A member whose
-- dates do not include the sheet's today is left out of its pool: no share in the total, no cut.
ALTER TABLE pool_member ADD COLUMN from_date DATE;
ALTER TABLE pool_member ADD COLUMN to_date DATE;
