-- The down payment a plan starts with: a percentage of the grand total (0 to 50, up to two
-- decimals) due on the issue date as instalment 0; 0 when there is none.
ALTER TABLE invoice ADD COLUMN plan_down_pct DECIMAL(5, 2) DEFAULT 0 NOT NULL;
