-- A sheet is the unit everything else is kept under. It is created the first time its id is loaded.
CREATE TABLE sheet (
    id         VARCHAR(100) PRIMARY KEY,
    created_by VARCHAR(100) NOT NULL
);
