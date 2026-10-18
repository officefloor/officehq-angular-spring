-- The sheet's floor: a claim's live due is at least this (two decimals). It starts at 1.00.
ALTER TABLE sheet ADD COLUMN floor DECIMAL(15, 2) DEFAULT 1.00 NOT NULL;
