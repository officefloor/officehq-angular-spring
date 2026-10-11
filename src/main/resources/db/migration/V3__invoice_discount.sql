-- A draft's percentage discount off its subtotal, 0 to 100 with up to two decimals; 0 is none.
ALTER TABLE invoice ADD COLUMN discount_pct DECIMAL(5, 2) DEFAULT 0 NOT NULL;
