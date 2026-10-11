-- The currency a client is billed in (USD, EUR, GBP, JPY or CHF); clients are billed in US dollars by default.
ALTER TABLE client ADD COLUMN currency VARCHAR(3) DEFAULT 'USD' NOT NULL;
