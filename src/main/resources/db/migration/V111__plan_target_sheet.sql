-- A plan may name one of its sheet's descendants: its claims are then generated on that sheet, for
-- the person of the same name there, by that sheet's rules. Null for the plan's own sheet.
ALTER TABLE plan ADD COLUMN target_sheet_id VARCHAR(100);
