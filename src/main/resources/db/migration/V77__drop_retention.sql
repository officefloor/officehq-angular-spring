-- Retention is no longer held back: every invoice owes its whole amount once it is sent.
ALTER TABLE client DROP COLUMN retention_pct;
ALTER TABLE invoice DROP COLUMN retention_pct;
ALTER TABLE invoice DROP COLUMN retention_cap;
ALTER TABLE invoice DROP COLUMN retention_released_on;
ALTER TABLE invoice DROP COLUMN retention_released_amount;
ALTER TABLE invoice DROP COLUMN credit_retention;
