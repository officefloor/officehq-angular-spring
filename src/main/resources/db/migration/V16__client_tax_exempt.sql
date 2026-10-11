-- A tax-exempt client (a charity, say) is charged no tax on any invoice line; clients are taxed by default.
ALTER TABLE client ADD COLUMN tax_exempt BOOLEAN DEFAULT FALSE NOT NULL;
