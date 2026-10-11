-- Whether the payment has been reversed (a bounced cheque): it then no longer counts anywhere.
ALTER TABLE payment ADD COLUMN reversed BOOLEAN DEFAULT FALSE NOT NULL;
