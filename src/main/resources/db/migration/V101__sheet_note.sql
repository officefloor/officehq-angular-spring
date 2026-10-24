-- The notes on a sheet, one row each: its text, who added it and the sheet's today when it was added.
CREATE TABLE sheet_note (
    id         BIGINT AUTO_INCREMENT PRIMARY KEY,
    sheet_id   VARCHAR(100)  NOT NULL REFERENCES sheet (id),
    added_on   DATE          NOT NULL,
    added_by   VARCHAR(100)  NOT NULL,
    note_text  VARCHAR(1000) NOT NULL
);
