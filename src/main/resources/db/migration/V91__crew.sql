-- The sheet's crews, each name once, stored without surrounding spaces, each with a lead from its people.
CREATE TABLE crew (
    id             BIGINT AUTO_INCREMENT PRIMARY KEY,
    sheet_id       VARCHAR(100) NOT NULL REFERENCES sheet (id),
    name           VARCHAR(200) NOT NULL,
    lead_person_id BIGINT       NOT NULL REFERENCES person (id)
);
CREATE UNIQUE INDEX crew_unique ON crew (sheet_id, name);
-- A crew's members, each person at most once in a crew, never its lead.
CREATE TABLE crew_member (
    id        BIGINT AUTO_INCREMENT PRIMARY KEY,
    crew_id   BIGINT       NOT NULL REFERENCES crew (id),
    sheet_id  VARCHAR(100) NOT NULL REFERENCES sheet (id),
    person_id BIGINT       NOT NULL REFERENCES person (id)
);
CREATE UNIQUE INDEX crew_member_unique ON crew_member (crew_id, person_id);
