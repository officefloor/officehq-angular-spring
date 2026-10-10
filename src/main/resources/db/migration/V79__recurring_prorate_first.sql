-- The first invoice of a recurring schedule may be pro-rated by the days left in its billing period.
-- prorate_first stays set until that first invoice has been raised.
ALTER TABLE recurring_invoice ADD COLUMN period_days INT DEFAULT 30 NOT NULL;
ALTER TABLE recurring_invoice ADD COLUMN prorate_first BOOLEAN DEFAULT FALSE NOT NULL;
ALTER TABLE recurring_invoice ADD CONSTRAINT recurring_invoice_period_days_positive CHECK (period_days > 0);
