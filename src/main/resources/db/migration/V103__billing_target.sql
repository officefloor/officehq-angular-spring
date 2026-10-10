-- The billings target: what the business aims to bill over the year, in the home currency. Null when no target is set.
ALTER TABLE app_settings ADD COLUMN billing_target DECIMAL(15, 2);
ALTER TABLE app_settings ADD CONSTRAINT app_settings_billing_target_positive CHECK (billing_target IS NULL OR billing_target > 0);
