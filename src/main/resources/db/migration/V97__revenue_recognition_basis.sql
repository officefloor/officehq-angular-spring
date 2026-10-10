-- When revenue counts: when an invoice is sent ('sent') or once it is paid ('paid'); existing data keeps
-- counting it when sent.
ALTER TABLE app_settings ADD COLUMN revenue_recognition_basis VARCHAR(10) DEFAULT 'sent' NOT NULL;
ALTER TABLE app_settings ADD CONSTRAINT app_settings_revenue_recognition_basis_valid
    CHECK (revenue_recognition_basis IN ('sent', 'paid'));
