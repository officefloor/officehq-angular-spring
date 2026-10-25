-- A sheet's parent: the sheet whose rates (then its parent's, up to three levels up) are used where the
-- sheet has none in force. Not dated, and not part of the sheet's as-of history: the sheet is keyed by
-- child_id rather than sheet_id, and neither column references sheet, so the as-of view keeps the parent
-- the sheet names now.
CREATE TABLE sheet_parent (
    child_id  VARCHAR(100) PRIMARY KEY,
    parent_id VARCHAR(100) NOT NULL
);
