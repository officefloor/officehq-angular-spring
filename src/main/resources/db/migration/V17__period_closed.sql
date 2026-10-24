-- Whether a period is closed, and its due-total fixed when it was closed; later rate changes leave
-- that total as it is. No claim dated in a closed period can be added, edited or change status.
ALTER TABLE period ADD COLUMN closed BOOLEAN DEFAULT FALSE NOT NULL;
ALTER TABLE period ADD COLUMN closed_total DECIMAL(12, 2);
