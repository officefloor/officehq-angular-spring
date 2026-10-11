-- Whether the line is work out of hours, charged at time and a half: its unit price (and each band's price
-- when tiered) is 1.5 times its stored price, rounded half up to the cent.
ALTER TABLE invoice_line ADD COLUMN overtime BOOLEAN DEFAULT FALSE NOT NULL;
