-- A plan may name one of its sheet's descendants: its claims are then made for the person of the same name on
-- that sheet. Existing plans name none and make their claims on their own sheet.
ALTER TABLE plan ADD COLUMN target_sheet VARCHAR(200);
