-- Whether a period is closed, and its due-total fixed when it was closed: later rate changes do not
-- alter it. Null while the period is open.
ALTER TABLE period ADD COLUMN closed BOOLEAN DEFAULT FALSE NOT NULL;
ALTER TABLE period ADD COLUMN closed_total DECIMAL(14, 2);
