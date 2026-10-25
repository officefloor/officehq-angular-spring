-- A plan may name one of its sheet's descendants: its claims are then made for the person of the same
-- name on that sheet, by that sheet's rules. Null when the plan's claims are made on its own sheet.
ALTER TABLE person_plan ADD COLUMN target_sheet_id VARCHAR(100);
