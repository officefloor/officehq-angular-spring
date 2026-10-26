-- The sheet's today when a transfer brought the person to this sheet; null when they were not transferred here.
ALTER TABLE person ADD COLUMN arrived_on DATE;
