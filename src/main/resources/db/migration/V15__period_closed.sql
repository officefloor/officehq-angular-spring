-- A period can be closed: its due-total is then fixed at what it was when it closed, and no claim dated in it
-- can change.
ALTER TABLE period ADD COLUMN closed       BOOLEAN       DEFAULT FALSE NOT NULL;
ALTER TABLE period ADD COLUMN closed_total DECIMAL(19, 2);
