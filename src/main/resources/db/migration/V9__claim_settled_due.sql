-- A claim's due is fixed when it is settled: what was due on its amount at its person's grade's
-- rate at that moment. None while it is not settled, or when the grade had no rate then (its due
-- stays live).
ALTER TABLE claim ADD COLUMN settled_due DECIMAL(12, 2);
