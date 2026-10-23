-- A crew on a sheet: a name (trimmed, once per sheet) and a lead, a person on the sheet.
CREATE TABLE crew (
    id       BIGINT AUTO_INCREMENT PRIMARY KEY,
    sheet_id VARCHAR(64)  NOT NULL REFERENCES sheet (id),
    name     VARCHAR(100) NOT NULL,
    lead_id  BIGINT       NOT NULL REFERENCES person (id)
);
CREATE UNIQUE INDEX crew_sheet_name ON crew (sheet_id, name);

-- A crew's member: a person on the sheet, in the crew at most once and never its lead. A person may be
-- in several crews.
CREATE TABLE crew_member (
    id        BIGINT AUTO_INCREMENT PRIMARY KEY,
    crew_id   BIGINT NOT NULL REFERENCES crew (id),
    person_id BIGINT NOT NULL REFERENCES person (id)
);
CREATE UNIQUE INDEX crew_member_crew_person ON crew_member (crew_id, person_id);
