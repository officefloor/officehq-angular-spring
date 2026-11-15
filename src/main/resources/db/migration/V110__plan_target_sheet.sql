-- A plan may name one of its sheet's descendants: its claims are then made on that sheet, for the
-- person of the same name there. Null: the plan's own sheet.
ALTER TABLE plan ADD COLUMN target_sheet VARCHAR(100);
