-- The descendant sheet a plan makes its claims on, for the person of the same name there; empty for the plan's own
-- sheet.
ALTER TABLE plan ADD COLUMN target_sheet_id VARCHAR(100);
