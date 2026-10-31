-- A sheet: everything in the app is kept per sheet. A row is created the first time its id is loaded.
CREATE TABLE sheet (
    id         VARCHAR(100) PRIMARY KEY,
    created_by VARCHAR(100) NOT NULL,
    created_at TIMESTAMP    NOT NULL
);
