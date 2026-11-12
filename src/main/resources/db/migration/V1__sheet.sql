-- A sheet: everything else in the app is kept per sheet, keyed by its id.
CREATE TABLE sheet (
    id         VARCHAR(200) PRIMARY KEY,
    created_by VARCHAR(200) NOT NULL,
    created_at TIMESTAMP    NOT NULL
);
