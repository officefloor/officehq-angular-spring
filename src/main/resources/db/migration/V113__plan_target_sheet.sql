-- A plan may name one of its sheet's descendants: its claims are then made on that sheet, for the person
-- there of the same name, by that sheet's rules. Null: the plan's own sheet and person.
ALTER TABLE plan ADD COLUMN target_sheet_id VARCHAR(64);
