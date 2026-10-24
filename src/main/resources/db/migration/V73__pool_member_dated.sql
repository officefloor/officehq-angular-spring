-- A pool membership may carry a from-date and a to-date (both optional, the to-date not before the from-date); a
-- member whose dates do not include the sheet's today is left out of the pool's total share, balance and cuts.
ALTER TABLE sheet_pool_member ADD COLUMN from_date DATE;
ALTER TABLE sheet_pool_member ADD COLUMN to_date DATE;
