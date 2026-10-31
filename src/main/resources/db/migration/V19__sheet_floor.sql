-- The sheet's floor: a claim's live due is at least this. Fixed figures (settled, stated, a closed
-- period's total) are left as they were.
ALTER TABLE sheet ADD COLUMN floor DECIMAL(14, 2) DEFAULT 1.00 NOT NULL CHECK (floor >= 0);
