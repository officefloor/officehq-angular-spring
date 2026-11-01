-- A person may have their own terms in days, used for their claims' due-by ahead of their grade's
-- and the sheet's; null: none.
ALTER TABLE person ADD COLUMN terms INT;
