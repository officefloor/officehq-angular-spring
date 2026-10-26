-- The due a claim had when it was settled, kept so later rate changes do not alter it.
-- Null for a claim not settled, or settled while its person's grade had no rate (its due stays live).
ALTER TABLE claim ADD COLUMN settled_due DECIMAL(14, 2);
