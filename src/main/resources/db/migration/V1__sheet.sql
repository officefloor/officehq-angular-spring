-- A sheet: everything else in the app is kept per sheet. A sheet is created the first time it is loaded.
CREATE TABLE sheet (
    id         VARCHAR(100) PRIMARY KEY,
    created_at TIMESTAMP    NOT NULL
);
