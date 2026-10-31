-- A settled claim's due, fixed when it is settled: its amount times its person's grade's rate at
-- that moment. Later changes to rates do not alter it. None while the claim is not settled, or when
-- the grade had no rate when it was settled.
ALTER TABLE claim ADD COLUMN settled_due DECIMAL(14, 2);
