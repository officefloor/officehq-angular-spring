-- Each client is billed in their own currency (ISO 4217 code); existing clients keep US dollars.
ALTER TABLE client ADD COLUMN currency VARCHAR(3) DEFAULT 'USD' NOT NULL;
ALTER TABLE client ADD CONSTRAINT client_currency_known CHECK (currency IN ('USD', 'EUR', 'GBP', 'CAD', 'AUD'));
