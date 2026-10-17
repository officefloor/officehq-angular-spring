-- The sheet's floor: every live due (not a settled claim's fixed due) is at least it.
ALTER TABLE sheet ADD COLUMN floor DECIMAL(14, 2) DEFAULT 1.00 NOT NULL;
