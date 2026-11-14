-- A sheet's crews: each a name, on a sheet at most once, a lead chosen from the sheet's people, and
-- its members, other people of the sheet. A person is in a crew at most once and is never a member
-- of the crew they lead; they may be in several crews.
CREATE TABLE crew (
    id BIGINT AUTO_INCREMENT PRIMARY KEY,
    sheet_id VARCHAR(200) NOT NULL REFERENCES sheet (id),
    name VARCHAR(255) NOT NULL,
    lead_id BIGINT NOT NULL REFERENCES person (id)
);
CREATE UNIQUE INDEX crew_sheet_name ON crew (sheet_id, name);

CREATE TABLE crew_member (
    id BIGINT AUTO_INCREMENT PRIMARY KEY,
    crew_id BIGINT NOT NULL REFERENCES crew (id),
    person_id BIGINT NOT NULL REFERENCES person (id)
);
CREATE UNIQUE INDEX crew_member_crew_person ON crew_member (crew_id, person_id);
