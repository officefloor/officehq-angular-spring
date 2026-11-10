-- A steward's flag on a person: its reason and the last day it is in force; null for none.
ALTER TABLE person ADD COLUMN flag_reason VARCHAR(500);
ALTER TABLE person ADD COLUMN flag_until DATE;
