-- How much of a payment's unused credit has been refunded to the client: it can no longer be used.
ALTER TABLE payment ADD COLUMN refunded DECIMAL(19, 2) DEFAULT 0 NOT NULL;
