-- The flag a steward put on a person: a reason and the last day it is in force. While the sheet's
-- today is on or before that day, only a steward may open a claim of theirs; null when never flagged.
ALTER TABLE person ADD COLUMN flag_reason VARCHAR(200);
ALTER TABLE person ADD COLUMN flag_until DATE;
