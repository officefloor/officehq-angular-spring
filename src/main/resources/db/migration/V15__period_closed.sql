-- A period can be closed: closing fixes its due-total at that moment (closed_total), so later
-- changes to rates do not alter it. Null while the period is open. No claim dated inside a closed
-- period can be added, edited or moved through its statuses.
ALTER TABLE period ADD COLUMN closed BOOLEAN DEFAULT FALSE NOT NULL;
ALTER TABLE period ADD COLUMN closed_total DECIMAL(14, 2);
