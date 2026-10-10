-- Clients are grouped into revenue bands by how much revenue they bring in, in the home currency: "high" from the
-- high threshold up, "medium" from the medium threshold up to the high one, and "low" below that.
ALTER TABLE app_settings ADD COLUMN revenue_band_medium_from DECIMAL(15, 2) DEFAULT 1000 NOT NULL;
ALTER TABLE app_settings ADD COLUMN revenue_band_high_from DECIMAL(15, 2) DEFAULT 5000 NOT NULL;
ALTER TABLE app_settings ADD CONSTRAINT app_settings_revenue_bands_ordered
    CHECK (revenue_band_medium_from > 0 AND revenue_band_high_from > revenue_band_medium_from);
