-- A person merged into another is kept aside rather than removed, so the merge can be undone: the row
-- names the person merged into, and is left out of the sheet while it does. Its name key is freed
-- while it is aside (see Person#park).
ALTER TABLE person ADD COLUMN merged_into BIGINT;

-- Each merge of a person into another on a sheet: who made it, on which sheet-today, the claims that
-- moved with it, and whether it was undone.
CREATE TABLE person_merge (
    id        BIGINT AUTO_INCREMENT PRIMARY KEY,
    sheet_id  VARCHAR(64)  NOT NULL REFERENCES sheet (id),
    person_id BIGINT       NOT NULL,
    into_id   BIGINT       NOT NULL,
    merged_by VARCHAR(200) NOT NULL,
    merged_on DATE,
    undone    BOOLEAN      DEFAULT FALSE NOT NULL
);
CREATE INDEX person_merge_into ON person_merge (sheet_id, into_id, id);

CREATE TABLE person_merge_claim (
    merge_id BIGINT NOT NULL REFERENCES person_merge (id),
    claim_id BIGINT NOT NULL,
    PRIMARY KEY (merge_id, claim_id)
);
