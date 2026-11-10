-- The sheet's pot (none: no pot): shared out over each balanced pool's members as their cuts.
ALTER TABLE sheet ADD COLUMN pot DECIMAL(14, 2) CHECK (pot >= 0);
