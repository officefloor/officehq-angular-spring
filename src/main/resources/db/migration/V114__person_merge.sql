-- Each merge of a person into another on a sheet, so the last merge into a person can be undone: who made
-- it, the sheet's today then, the merged person as they were (as JSON: name, grades, terms, contact, tags,
-- aliases, holds and the like), the ids of the claims that moved, and whether it was undone.
CREATE TABLE person_merge (
    id         BIGINT AUTO_INCREMENT PRIMARY KEY,
    sheet_id   VARCHAR(100) NOT NULL REFERENCES sheet (id),
    into_id    BIGINT       NOT NULL,
    person_id  BIGINT       NOT NULL,
    merged_by  VARCHAR(100) NOT NULL,
    merged_on  DATE         NOT NULL,
    person     CLOB         NOT NULL,
    claim_ids  VARCHAR(100000) NOT NULL,
    undone     BOOLEAN      NOT NULL DEFAULT FALSE
);
CREATE INDEX person_merge_into ON person_merge (sheet_id, into_id, id);
