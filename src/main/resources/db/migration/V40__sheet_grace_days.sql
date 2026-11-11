-- The sheet's grace days: when its today moves past an open period's to-date by more than these, the period closes
-- itself. Not dated; null means off.
ALTER TABLE sheet ADD COLUMN grace_days INT;
