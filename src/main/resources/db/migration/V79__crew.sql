-- The sheet's crews: a name (stored trimmed, once per sheet) and a lead, a person of the sheet, and its
-- members, each a person of the sheet other than the lead, in the crew at most once.
CREATE TABLE crew (
    id       BIGINT AUTO_INCREMENT PRIMARY KEY,
    sheet_id VARCHAR(100) NOT NULL REFERENCES sheet (id),
    name     VARCHAR(200) NOT NULL,
    lead_id  BIGINT       NOT NULL REFERENCES person (id)
);
CREATE UNIQUE INDEX crew_once ON crew (sheet_id, name);

CREATE TABLE crew_member (
    id        BIGINT AUTO_INCREMENT PRIMARY KEY,
    crew_id   BIGINT NOT NULL REFERENCES crew (id),
    person_id BIGINT NOT NULL REFERENCES person (id)
);
CREATE UNIQUE INDEX crew_member_once ON crew_member (crew_id, person_id);
