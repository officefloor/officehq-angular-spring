-- Retention held back until a job is signed off: a percentage (0 to 20) of the grand total, and the
-- day it was released (null while it is still held). Released retention falls due 30 days later.
ALTER TABLE invoice ADD COLUMN retention_pct DECIMAL(5, 2) DEFAULT 0 NOT NULL;
ALTER TABLE invoice ADD COLUMN retention_released_on DATE;
