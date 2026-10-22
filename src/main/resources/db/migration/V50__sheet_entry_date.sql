-- Entry date: while set, the default date of a new claim wherever the sheet's today was (the claim
-- form, the quick-add bar, a pasted line with a blank date and a correction); null: off.
ALTER TABLE sheet ADD COLUMN entry_date DATE;
