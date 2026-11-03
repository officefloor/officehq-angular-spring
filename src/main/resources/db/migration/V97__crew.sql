-- The crews of a sheet, each (trimmed) name at most once, each with a lead chosen from the people,
-- and their members: people of the sheet, each at most once in a crew and never its lead. People are
-- held by id only, so a person leaving the sheet just drops out of the crews.
CREATE TABLE crew (
    id             BIGINT AUTO_INCREMENT PRIMARY KEY,
    sheet_id       VARCHAR(100) NOT NULL REFERENCES sheet (id),
    name           VARCHAR(200) NOT NULL,
    lead_person_id BIGINT       NOT NULL,
    CONSTRAINT crew_unique UNIQUE (sheet_id, name)
);

CREATE TABLE crew_member (
    id        BIGINT AUTO_INCREMENT PRIMARY KEY,
    crew_id   BIGINT NOT NULL REFERENCES crew (id),
    person_id BIGINT NOT NULL,
    CONSTRAINT crew_member_unique UNIQUE (crew_id, person_id)
);
