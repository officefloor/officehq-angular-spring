-- A crew on the sheet: a name (at most once per sheet, compared ignoring case) and its lead, a
-- person of the sheet.
CREATE TABLE crew (
    id         BIGINT AUTO_INCREMENT PRIMARY KEY,
    sheet_id   VARCHAR(100) NOT NULL REFERENCES sheet (id),
    name       VARCHAR(200) NOT NULL,
    lead_id    BIGINT NOT NULL REFERENCES person (id)
);
CREATE INDEX crew_sheet ON crew (sheet_id, id);

-- A member of a crew: a person of the sheet, at most once per crew and never its lead.
CREATE TABLE crew_member (
    id         BIGINT AUTO_INCREMENT PRIMARY KEY,
    crew_id    BIGINT NOT NULL REFERENCES crew (id),
    person_id  BIGINT NOT NULL REFERENCES person (id),
    UNIQUE (crew_id, person_id)
);
