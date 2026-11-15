-- A note on the sheet: its text, the user who added it and the sheet's today when it was added.
-- Notes are only ever added; under an as-of date the sheet shows those added on or before it.
CREATE TABLE sheet_note (
    id        BIGINT AUTO_INCREMENT PRIMARY KEY,
    sheet_id  VARCHAR(100) NOT NULL REFERENCES sheet (id),
    added_on  DATE NOT NULL,
    added_by  VARCHAR(100) NOT NULL,
    text      VARCHAR(1000) NOT NULL
);
CREATE INDEX sheet_note_sheet ON sheet_note (sheet_id, id);
