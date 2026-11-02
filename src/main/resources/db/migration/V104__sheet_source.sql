-- The sheet a sheet was cloned from (not dated): a grade with no rate in force on a date on the sheet
-- nor on its parents takes the source's, then its parents', then its own source's, and so on. Kept apart
-- from the sheet's own rows (no sheet_id column, no reference) like sheet_parent, so neither a clone nor
-- a sheet's history copies or changes it.
CREATE TABLE sheet_source (
    child_sheet  VARCHAR(100) PRIMARY KEY,
    source_sheet VARCHAR(100) NOT NULL
);
