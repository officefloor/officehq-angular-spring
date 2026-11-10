-- Grace days (none: off): when the sheet's today changes, every open period whose to-date plus the
-- grace days is before the new today is closed. Not dated.
ALTER TABLE sheet ADD COLUMN grace_days INT;
