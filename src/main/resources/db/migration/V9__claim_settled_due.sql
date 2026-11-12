-- A settled claim's due, fixed when it was settled: its amount times its person's grade's rate at
-- that moment, divided by 100, rounded half up to cents. None while DRAFT or OPEN, and none when
-- the grade had no rate then (such a claim's due stays live).
ALTER TABLE claim ADD COLUMN settled_due DECIMAL(15, 2);
