-- The crews on a sheet: each name is on a sheet at most once, trimmed, with a lead chosen from the sheet's people
-- (none once the lead has left the sheet). A crew's members are people of the sheet, each in it at most once and
-- never its lead. A person can be in several crews.
CREATE TABLE crew (
    id       BIGINT AUTO_INCREMENT PRIMARY KEY,
    sheet_id VARCHAR(100) NOT NULL REFERENCES sheet (id),
    name     VARCHAR(200) NOT NULL,
    lead_id  BIGINT REFERENCES person (id)
);
CREATE UNIQUE INDEX crew_sheet_name ON crew (sheet_id, name);

CREATE TABLE crew_member (
    id        BIGINT AUTO_INCREMENT PRIMARY KEY,
    crew_id   BIGINT NOT NULL REFERENCES crew (id),
    person_id BIGINT NOT NULL REFERENCES person (id)
);
CREATE UNIQUE INDEX crew_member_crew_person ON crew_member (crew_id, person_id);
