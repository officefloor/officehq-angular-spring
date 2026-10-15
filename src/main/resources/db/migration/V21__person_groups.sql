-- Groups of people: each has a name and belongs to one sheet (null for the unnamed sheet), numbered
-- within it like people. "group" is a reserved word, so the table is person_group.
CREATE TABLE person_group (
    id BIGINT AUTO_INCREMENT PRIMARY KEY,
    sheet_id VARCHAR(255) REFERENCES sheet (id) ON DELETE CASCADE,
    local_id BIGINT NOT NULL,
    name VARCHAR(255) NOT NULL,
    sheet_key VARCHAR(255) GENERATED ALWAYS AS (COALESCE(sheet_id, ''))
);
CREATE UNIQUE INDEX person_group_sheet_local_id ON person_group (sheet_key, local_id);
-- A person is in one group; absent until put in one.
ALTER TABLE person ADD COLUMN group_id BIGINT REFERENCES person_group (id) ON DELETE SET NULL;
