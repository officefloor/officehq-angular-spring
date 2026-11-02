-- The due a claim had when it was settled (its amount times its person's grade rate, two decimals),
-- kept so later changes to rates do not alter it. Null for a claim not settled, or settled while
-- its person's grade had no rate.
ALTER TABLE claim ADD COLUMN settled_due DECIMAL(14, 2);
