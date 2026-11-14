-- A cancelled statement is kept, marked with the sheet's today when it was cancelled, so the sheet as
-- it stood at the end of an earlier day still shows it; it no longer counts now. Null: not cancelled.
ALTER TABLE statement ADD COLUMN cancelled_on DATE;
