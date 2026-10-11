-- The conversion fee a payment in a currency other than the client's carried: the part of its amount
-- that is the workspace's conversion fee, which pays no invoice and is held as no credit; 0 otherwise.
ALTER TABLE payment ADD COLUMN fx_fee DECIMAL(19, 2) DEFAULT 0 NOT NULL;
