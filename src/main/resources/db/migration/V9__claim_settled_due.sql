-- The due a claim had when it was settled (amount times its person's grade's rate, rounded half up
-- to cents), kept so later changes to rates do not alter it. Null for a claim that is not settled,
-- or that was settled while its grade had no rate.
ALTER TABLE claim ADD COLUMN settled_due DECIMAL(14, 2);
