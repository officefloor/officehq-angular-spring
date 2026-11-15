-- The sheet's today on which a transfer brought the person to the sheet: their transfer may be
-- undone on that day only. Null for anyone who did not arrive by transfer.
ALTER TABLE person ADD COLUMN arrived_on DATE;
