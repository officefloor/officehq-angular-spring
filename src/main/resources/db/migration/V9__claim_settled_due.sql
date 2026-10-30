-- A claim's due fixed when it was settled: the amount at its person's grade rate at that moment.
-- Null while the claim is not settled, or when the grade had no rate then (the due stays live).
ALTER TABLE claim ADD COLUMN settled_due DECIMAL(14, 2);
