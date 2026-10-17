-- A period can be closed; closing fixes its due-total at that moment (null while it is open).
ALTER TABLE period ADD COLUMN closed BOOLEAN DEFAULT FALSE NOT NULL;
ALTER TABLE period ADD COLUMN closed_total DECIMAL(14, 2);
