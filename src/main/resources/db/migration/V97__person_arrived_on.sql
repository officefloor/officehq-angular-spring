-- The sheet's today when a transfer brought the person to it; null when they did not arrive by transfer.
ALTER TABLE person ADD COLUMN arrived_on DATE;
