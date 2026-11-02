-- Whether a period is closed, and the due-total it had when it was closed, kept so later changes
-- to rates do not alter it. No claim dated inside a closed period can be added, edited or change
-- its status.
ALTER TABLE period ADD COLUMN closed BOOLEAN DEFAULT FALSE NOT NULL;
ALTER TABLE period ADD COLUMN closed_total DECIMAL(14, 2);
