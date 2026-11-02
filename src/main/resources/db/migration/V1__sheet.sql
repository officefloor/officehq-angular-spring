-- A sheet: everything else in the app is kept per sheet. Created the first time its id is loaded.
CREATE TABLE sheet (
    id         VARCHAR(100) PRIMARY KEY,
    created_by VARCHAR(100) NOT NULL
);
