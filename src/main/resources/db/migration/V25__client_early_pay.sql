-- An early-payment discount: a percentage (0 is none) of the grand total the client may take off when
-- an invoice is paid off within early_pay_days of its issue date (null when there is no window).
ALTER TABLE client ADD COLUMN early_pay_pct DECIMAL(5, 2) DEFAULT 0 NOT NULL;
ALTER TABLE client ADD COLUMN early_pay_days INT;
-- The early discount an invoice was settled with; 0 when none was taken.
ALTER TABLE invoice ADD COLUMN early_discount DECIMAL(19, 2) DEFAULT 0 NOT NULL;
