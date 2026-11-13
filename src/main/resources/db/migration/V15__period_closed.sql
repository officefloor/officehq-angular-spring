-- A period can be closed. Closing fixes its due-total at that moment (two decimals): later changes
-- to rates do not alter it. No claim dated inside a closed period can be added, edited, opened,
-- settled or voided.
ALTER TABLE period ADD COLUMN closed BOOLEAN DEFAULT FALSE NOT NULL;
ALTER TABLE period ADD COLUMN closed_total DECIMAL(12, 2);
