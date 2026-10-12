-- The percentage (0 for none) a client's typed recurring prices go up by on each anniversary of the plan.
ALTER TABLE client ADD COLUMN uplift_pct DECIMAL(5, 2) NOT NULL DEFAULT 0;
-- The day a recurring invoice's plan began (moved on a year at each price rise); null when it has none.
ALTER TABLE invoice ADD COLUMN recurring_since DATE;
