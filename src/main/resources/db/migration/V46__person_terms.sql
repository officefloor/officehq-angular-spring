-- A person may have their own terms: their claims are due that many days after their date, ahead of
-- their grade's terms and the sheet's. None (null) when they have no terms of their own.
ALTER TABLE person ADD COLUMN terms INT CHECK (terms >= 0);
