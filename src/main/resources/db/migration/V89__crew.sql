-- The sheet's crews, each name at most once, each with a lead chosen from the sheet's people, and their members: each
-- person at most once in a crew, never its lead, though they may be in several crews.
CREATE TABLE crew (
    id       BIGINT AUTO_INCREMENT PRIMARY KEY,
    sheet_id VARCHAR(100) NOT NULL REFERENCES sheet (id),
    name     VARCHAR(140) NOT NULL,
    lead_id  BIGINT       NOT NULL REFERENCES person (id)
);
CREATE UNIQUE INDEX crew_name ON crew (sheet_id, name);

CREATE TABLE crew_member (
    id        BIGINT AUTO_INCREMENT PRIMARY KEY,
    sheet_id  VARCHAR(100) NOT NULL REFERENCES sheet (id),
    crew_id   BIGINT       NOT NULL REFERENCES crew (id),
    person_id BIGINT       NOT NULL REFERENCES person (id)
);
CREATE UNIQUE INDEX crew_member_once ON crew_member (crew_id, person_id);
