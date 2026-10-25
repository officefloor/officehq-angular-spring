-- The sheet a sheet was cloned from: its rates (then its parents', then its own source's) are used for a
-- grade and date that have no rate in force on this sheet nor on its parents; null when it was not cloned.
ALTER TABLE sheet ADD COLUMN source_id VARCHAR(100);
