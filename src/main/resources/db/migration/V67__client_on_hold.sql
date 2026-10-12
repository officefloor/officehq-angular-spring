-- Whether the client is on hold: their invoices are not sent, their credit is not applied, and their
-- payments pay no invoice but are held as credit.
ALTER TABLE client ADD COLUMN on_hold BOOLEAN DEFAULT FALSE NOT NULL;
