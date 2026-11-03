-- A claim's due fixed when it is settled: the amount times the rate for its person's grade at that
-- moment (rounded half up to the cent). Later rate or grade changes do not alter it. None while
-- the claim is not settled, or when the grade had no rate when it was settled (it then stays live).
ALTER TABLE claim ADD COLUMN settled_due DECIMAL(14, 2);
