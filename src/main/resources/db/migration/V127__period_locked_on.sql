-- The sheet's today when the period was last locked, so a period read as of an earlier day shows it
-- not locked then; null while it is not locked, or for one locked before this was kept.
ALTER TABLE period ADD COLUMN locked_on DATE;
