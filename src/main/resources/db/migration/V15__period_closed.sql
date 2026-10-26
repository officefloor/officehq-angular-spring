-- Whether a period is closed, and its due-total fixed when it was closed. No claim dated in a closed period can be
-- added, edited or change status; later rate changes do not move its due-total.
ALTER TABLE period ADD COLUMN closed BOOLEAN NOT NULL DEFAULT FALSE;
ALTER TABLE period ADD COLUMN closed_total DECIMAL(14, 2);
