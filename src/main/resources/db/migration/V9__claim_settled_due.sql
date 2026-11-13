-- A claim's due fixed when it is settled: the amount times its person's grade rate at that moment,
-- kept as it was whatever the rates do later. Null for a claim not settled, or settled while the
-- grade had no rate.
ALTER TABLE claim ADD COLUMN settled_due DECIMAL(12, 2);
