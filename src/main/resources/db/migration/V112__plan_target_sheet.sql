-- A plan may name one of its sheet's descendants: its claims are then made there, for the person of the same name on
-- that sheet, by that sheet's rules. Null: on the plan's own sheet.
ALTER TABLE plan ADD COLUMN target_sheet_id VARCHAR(100);
