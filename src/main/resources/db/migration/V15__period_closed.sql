-- Whether a period is closed, and its due-total fixed when it was closed: the dues of the OPEN and
-- SETTLED claims dated in it, skipping those with no rate. Null while the period is open. No claim
-- dated in a closed period can be added, edited, opened, settled or voided.
ALTER TABLE period ADD COLUMN closed BOOLEAN DEFAULT FALSE NOT NULL;
ALTER TABLE period ADD COLUMN closed_total DECIMAL(14, 2);
