-- Whether the payment was recorded as a deposit: the whole amount held on the client's account as
-- credit, applied to no invoice until one is sent.
ALTER TABLE payment ADD COLUMN deposit BOOLEAN DEFAULT FALSE NOT NULL;
