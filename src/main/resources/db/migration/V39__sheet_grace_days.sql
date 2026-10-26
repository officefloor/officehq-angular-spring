-- The days after a period's to-date it stays open once the sheet's today passes it; null (blank) for off.
ALTER TABLE sheet ADD COLUMN grace_days INTEGER CHECK (grace_days >= 0);
