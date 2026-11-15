-- A steward's flag on a person: its reason and the last day it is in force. While the sheet's
-- today is on or before that day only a steward may open the person's claims. Null when unflagged.
ALTER TABLE person ADD COLUMN flag_reason VARCHAR(200);
ALTER TABLE person ADD COLUMN flag_until DATE;
