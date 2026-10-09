-- App-wide settings, held as a single row (id 1). The default tax rate is the sales tax percentage a
-- new invoice starts with; it starts at zero (no tax).
CREATE TABLE app_settings (
    id BIGINT PRIMARY KEY,
    default_tax_pct DECIMAL(5, 2) DEFAULT 0 NOT NULL,
    CONSTRAINT app_settings_single_row CHECK (id = 1),
    CONSTRAINT app_settings_default_tax_pct_range CHECK (default_tax_pct >= 0 AND default_tax_pct <= 100)
);

INSERT INTO app_settings (id, default_tax_pct) VALUES (1, 0);
