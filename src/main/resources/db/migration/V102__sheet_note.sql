-- A sheet's notes: a text added by a user, kept with the sheet's today when it was added so the
-- sheet as of a past day lists only the notes added while its today was on or before that day.
CREATE TABLE sheet_note (
    id BIGINT AUTO_INCREMENT PRIMARY KEY,
    sheet_id VARCHAR(200) NOT NULL REFERENCES sheet (id),
    note_text VARCHAR(1000) NOT NULL,
    added_by VARCHAR(200) NOT NULL,
    added_on DATE NOT NULL
);
CREATE INDEX sheet_note_sheet ON sheet_note (sheet_id, id);
