-- A sheet: the unit everything else in the app is kept per. Created the first time its id is loaded.
CREATE TABLE sheet (
    id         VARCHAR(64) PRIMARY KEY,
    created_at TIMESTAMP   NOT NULL
);
