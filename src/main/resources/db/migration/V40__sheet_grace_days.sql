-- The sheet's grace days: when its today changes, every open period whose to-date plus these is before the new
-- today is closed. None means off. Not dated.
ALTER TABLE sheet ADD COLUMN grace_days INT;
