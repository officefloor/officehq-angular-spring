-- A closed period: its due-total is fixed at the moment it was closed (closed_total), so later rate
-- changes leave it as it is, and no claim dated inside it can be added, edited or moved.
ALTER TABLE period ADD COLUMN closed BOOLEAN DEFAULT FALSE NOT NULL;
ALTER TABLE period ADD COLUMN closed_total DECIMAL(14, 2);
