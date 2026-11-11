-- A claim's due as it was when it was settled: its amount times its person's grade's rate then,
-- rounded half up to cents. Null while the claim is not settled, or when the grade had no rate
-- (that due stays live).
ALTER TABLE claim ADD COLUMN settled_due DECIMAL(14, 2);
