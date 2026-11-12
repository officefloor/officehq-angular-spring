-- The crews of a sheet, each name at most once (kept trimmed), each with a lead chosen from the sheet's people.
CREATE TABLE crew (
    id             BIGINT AUTO_INCREMENT PRIMARY KEY,
    sheet_id       VARCHAR(100) NOT NULL REFERENCES sheet (id),
    name           VARCHAR(255) NOT NULL,
    lead_person_id BIGINT       NOT NULL REFERENCES person (id)
);
CREATE UNIQUE INDEX crew_sheet_name ON crew (sheet_id, name);

-- The members of a crew besides its lead, each person at most once.
CREATE TABLE crew_member (
    id        BIGINT AUTO_INCREMENT PRIMARY KEY,
    crew_id   BIGINT NOT NULL REFERENCES crew (id),
    person_id BIGINT NOT NULL REFERENCES person (id)
);
CREATE UNIQUE INDEX crew_member_crew_person ON crew_member (crew_id, person_id);
