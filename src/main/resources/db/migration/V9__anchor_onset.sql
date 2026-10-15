-- A person's onset date; absent until given.
ALTER TABLE person ADD COLUMN onset DATE;
-- A sheet's anchor date, the fixed point for everything date-dependent; absent means today.
ALTER TABLE sheet ADD COLUMN anchor DATE;
-- The unnamed sheet has no row in sheet, so its settings live in this single-row table.
CREATE TABLE unnamed_sheet (
    id INT PRIMARY KEY CHECK (id = 1),
    anchor DATE
);
