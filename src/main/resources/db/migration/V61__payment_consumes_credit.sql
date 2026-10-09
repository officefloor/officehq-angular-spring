-- A lump payment can now use up the client's credit first: part of what it settles may come out of their held
-- deposits (from_deposits) and then their unused credit notes (from_credit_notes), on top of the money received.
-- Whatever of the money received is not needed for the invoices is kept as credit for the client (to_credit) and
-- is held alongside their deposits. The payments recorded against the invoices always add up to
-- amount + from_deposits + from_credit_notes - to_credit.
ALTER TABLE client_payment ADD COLUMN from_deposits DECIMAL(12, 2) DEFAULT 0 NOT NULL;
ALTER TABLE client_payment ADD COLUMN from_credit_notes DECIMAL(12, 2) DEFAULT 0 NOT NULL;
ALTER TABLE client_payment ADD COLUMN to_credit DECIMAL(12, 2) DEFAULT 0 NOT NULL;
ALTER TABLE client_payment ADD CONSTRAINT client_payment_credit_split CHECK (from_deposits >= 0
    AND from_credit_notes >= 0 AND to_credit >= 0 AND to_credit <= amount);
