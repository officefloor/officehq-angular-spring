-- The day a sent invoice was written off as a bad debt (null when it was not): everything still owed
-- on it that day, late charges and retention included, is written off; it stops earning interest,
-- owes nothing and takes no more payments.
ALTER TABLE invoice ADD COLUMN written_off_on DATE;
