-- The day the late fee and late interest still unpaid on an overdue invoice were last waived (null
-- when never). Interest starts again from that day on what is still owed; no new fee is charged.
ALTER TABLE invoice ADD COLUMN interest_waived_on DATE;
