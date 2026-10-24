-- The crews on a sheet, one row each by name (kept without the spaces around it), each led by a person on
-- the sheet.
CREATE TABLE sheet_crew (
    id             BIGINT AUTO_INCREMENT PRIMARY KEY,
    sheet_id       VARCHAR(100) NOT NULL REFERENCES sheet (id),
    name           VARCHAR(200) NOT NULL,
    lead_person_id BIGINT       NOT NULL REFERENCES person (id),
    CONSTRAINT sheet_crew_unique UNIQUE (sheet_id, name)
);
-- A crew's members: people on the crew's sheet other than its lead, each at most once per crew. A person can
-- be in several crews.
CREATE TABLE sheet_crew_member (
    id        BIGINT AUTO_INCREMENT PRIMARY KEY,
    crew_id   BIGINT NOT NULL REFERENCES sheet_crew (id),
    person_id BIGINT NOT NULL REFERENCES person (id),
    CONSTRAINT sheet_crew_member_unique UNIQUE (crew_id, person_id)
);
