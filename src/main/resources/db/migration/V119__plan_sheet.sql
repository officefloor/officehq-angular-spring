-- A plan may name one of its sheet's descendants: its claims are then made for the person of the
-- same name on that sheet (none when there is no such person). Null: the plan's own sheet.
ALTER TABLE plan ADD COLUMN target_sheet_id VARCHAR(100);
