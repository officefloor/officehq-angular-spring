-- A sheet: everything else in the app is kept per sheet. A sheet is created the first time its
-- id is loaded.
CREATE TABLE sheet (
    id         VARCHAR(100) PRIMARY KEY,
    created_by VARCHAR(100) NOT NULL
);
