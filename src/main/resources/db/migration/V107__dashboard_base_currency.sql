-- The currency the dashboard's totals are shown in, converted from the home currency at the latest rate; null
-- shows them in the home currency itself.
ALTER TABLE app_settings ADD COLUMN dashboard_base_currency VARCHAR(3);
ALTER TABLE app_settings ADD CONSTRAINT app_settings_dashboard_base_currency_fk FOREIGN KEY (dashboard_base_currency) REFERENCES currency (code);
