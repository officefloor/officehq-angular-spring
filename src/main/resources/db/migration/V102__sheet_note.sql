-- A note on a sheet: a text, who added it and the sheet's today when they did.
CREATE TABLE sheet_note (
    id         BIGINT AUTO_INCREMENT PRIMARY KEY,
    sheet_id   VARCHAR(64)   NOT NULL REFERENCES sheet (id),
    added_on   DATE          NOT NULL,
    added_by   VARCHAR(200)  NOT NULL,
    note_text  VARCHAR(2000) NOT NULL
);
CREATE INDEX sheet_note_sheet ON sheet_note (sheet_id);
