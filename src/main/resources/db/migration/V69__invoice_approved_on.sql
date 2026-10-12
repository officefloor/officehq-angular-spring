-- The day an invoice was approved; the approval lapses once more than 30 days have passed since then.
-- Null while it has not been approved (or for an approval given before the day was kept).
ALTER TABLE invoice ADD COLUMN approved_on DATE;
