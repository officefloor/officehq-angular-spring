-- The sheet's today when a statement was cancelled (null: it stands). A cancelled statement is kept
-- so the sheet as it stood before the cancel still shows it.
ALTER TABLE statement ADD COLUMN cancelled_on DATE;
