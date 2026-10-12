-- The day a sent invoice was rebilled (cancelled with a full credit note and replaced); null when it was not.
ALTER TABLE invoice ADD COLUMN rebilled_on DATE;
-- The id of the rebilled invoice this one replaces; null when it replaces none.
ALTER TABLE invoice ADD COLUMN replaces BIGINT;
-- The invoice a payment's credit is held for, when it was moved off a rebilled invoice; null for none.
ALTER TABLE payment ADD COLUMN held_for BIGINT;
