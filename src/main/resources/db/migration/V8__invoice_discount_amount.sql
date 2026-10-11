-- A draft's fixed discount off its subtotal, to the cent; 0 is none. An invoice has a percentage or
-- a fixed discount, never both.
ALTER TABLE invoice ADD COLUMN discount_amount DECIMAL(19, 2) DEFAULT 0 NOT NULL;
