-- A steward's flag on the person: its reason and the last day it is in force; null: never flagged.
ALTER TABLE person ADD COLUMN flag_reason VARCHAR(200);
ALTER TABLE person ADD COLUMN flag_until DATE;
