-- A plan may name one of its sheet's descendants: its claims are then made for the person of the same name there.
ALTER TABLE sheet_plan ADD COLUMN target_sheet_id VARCHAR(100);
