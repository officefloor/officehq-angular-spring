-- The sheet a sheet names as its parent (not dated): a grade with no rate in force on a date on the
-- sheet takes its parent's, then its parent's parent's, up to three levels up. Kept apart from the
-- sheet's own rows (no sheet_id column, no reference) so its history leaves it as it is now: the
-- link between sheets is not part of what either sheet held on a date.
CREATE TABLE sheet_parent (
    child_sheet  VARCHAR(100) PRIMARY KEY,
    parent_sheet VARCHAR(100) NOT NULL
);
