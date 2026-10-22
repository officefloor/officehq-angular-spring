-- The sheet's floor: a claim's live due is at least it. Settled, stated and closed figures keep
-- what they were fixed at.
ALTER TABLE sheet ADD COLUMN floor DECIMAL(14, 2) DEFAULT 1.00 NOT NULL;
