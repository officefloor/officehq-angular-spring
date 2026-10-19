-- A sheet is kept by the id a person types to load it; everything else in the app belongs to one.
CREATE TABLE sheet (
    id         VARCHAR(100) PRIMARY KEY,
    created_by VARCHAR(100) NOT NULL,
    created_at TIMESTAMP    NOT NULL
);
