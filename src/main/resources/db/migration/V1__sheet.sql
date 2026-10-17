-- A sheet: everything else in the app is kept per sheet. A row is created the first time the
-- sheet's id is loaded.
CREATE TABLE sheet (
    id         VARCHAR(100) NOT NULL PRIMARY KEY,
    created_by VARCHAR(100) NOT NULL
);
