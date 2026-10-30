-- The crews on a sheet, each name once, each led by a person of the sheet; and their members: people of the
-- sheet, each in a crew at most once and never its lead.
CREATE TABLE crew (
    id             BIGINT AUTO_INCREMENT PRIMARY KEY,
    sheet_id       VARCHAR(100) NOT NULL REFERENCES sheet (id),
    name           VARCHAR(100) NOT NULL,
    lead_person_id BIGINT NOT NULL REFERENCES person (id),
    CONSTRAINT crew_unique UNIQUE (sheet_id, name)
);
CREATE TABLE crew_member (
    id        BIGINT AUTO_INCREMENT PRIMARY KEY,
    crew_id   BIGINT NOT NULL REFERENCES crew (id),
    person_id BIGINT NOT NULL REFERENCES person (id),
    CONSTRAINT crew_member_unique UNIQUE (crew_id, person_id)
);
