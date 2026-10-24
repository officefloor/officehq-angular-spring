-- A statement's due-total is null when the person's due-total was n/a then (no claim with a figure
-- and no rate of their grade in force on the sheet's today).
ALTER TABLE statement ALTER COLUMN due_total SET NULL;
