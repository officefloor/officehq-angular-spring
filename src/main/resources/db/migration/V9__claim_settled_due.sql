-- Settling a claim fixes what is due on it: the amount times its person's grade's rate at that
-- moment, rounded half up to two decimals. Null while the claim is not settled, or when it was
-- settled while the grade had no rate; such a claim's due stays live.
ALTER TABLE claim ADD COLUMN settled_due DECIMAL(12, 2);
