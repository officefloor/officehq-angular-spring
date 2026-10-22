-- A pool membership may carry a from-date and a to-date, each optional: the member counts toward the
-- pool's total share and gets a cut only on days within them. A to-date is never before the from-date.
ALTER TABLE pool_member ADD COLUMN from_date DATE;
ALTER TABLE pool_member ADD COLUMN to_date DATE;
ALTER TABLE pool_member ADD CONSTRAINT pool_member_to_after_from
    CHECK (to_date IS NULL OR from_date IS NULL OR to_date >= from_date);
