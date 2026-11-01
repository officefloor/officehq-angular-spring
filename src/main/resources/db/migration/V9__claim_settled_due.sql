-- A claim's due, fixed when it is settled: its amount times its person's grade rate at that moment.
-- Empty while the claim is not settled, or when it was settled with no rate for the grade (its due
-- then stays live).
ALTER TABLE claim ADD COLUMN settled_due DECIMAL(14, 2);
