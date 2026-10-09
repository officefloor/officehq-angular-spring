-- A client can have a standard discount, a percentage that each new invoice for them starts with
-- applied. Existing clients have none.
ALTER TABLE client ADD COLUMN default_discount_pct DECIMAL(5, 2) DEFAULT 0 NOT NULL;
