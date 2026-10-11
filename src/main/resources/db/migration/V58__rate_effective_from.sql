-- The date a rate card or price list entry takes effect from; NULL for one that has always applied.
-- A unit type can have several entries, one per date: a line is priced at the latest in force on its
-- invoice's issue date.
ALTER TABLE client_rate DROP PRIMARY KEY;
ALTER TABLE client_rate ADD COLUMN effective_from DATE;
ALTER TABLE price_list_rate DROP PRIMARY KEY;
ALTER TABLE price_list_rate ADD COLUMN effective_from DATE;
