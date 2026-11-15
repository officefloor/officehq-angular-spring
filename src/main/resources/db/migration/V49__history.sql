-- Each version of a sheet's claims, people, rates and dated settings, as a change left it, with the
-- sheet's today when the change was made: a statement taken as of a date rebuilds the sheet from the
-- last version of each made while the sheet's today was on or before that date.
CREATE TABLE history (
    id         BIGINT AUTO_INCREMENT PRIMARY KEY,
    sheet_id   VARCHAR(100)   NOT NULL,
    kind       VARCHAR(20)    NOT NULL,
    entity_id  VARCHAR(100)   NOT NULL,
    made_on    DATE           NOT NULL,
    state      CLOB           NOT NULL
);
CREATE INDEX history_entity ON history (sheet_id, kind, entity_id, id);
-- The date a statement was taken as of (its figures as they stood at the end of it); none when it
-- was taken as the sheet stood when it was taken.
ALTER TABLE person_statement ADD COLUMN as_of DATE;
