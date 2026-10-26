-- The sheet a sheet was cloned from: where a grade has no rate in force on the sheet nor on its parents,
-- the source's rates are used (then its parents', then its own source's, and so on). Kept by child_id, like
-- sheet_parent, so it is not part of the sheet's as-of history.
CREATE TABLE sheet_source (
    child_id  VARCHAR(100) PRIMARY KEY,
    source_id VARCHAR(100) NOT NULL
);
