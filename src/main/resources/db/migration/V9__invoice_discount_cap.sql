-- The most a draft's percentage discount may take off, to the cent; NULL is no cap. A fixed discount
-- amount is not capped.
ALTER TABLE invoice ADD COLUMN discount_cap DECIMAL(19, 2);
