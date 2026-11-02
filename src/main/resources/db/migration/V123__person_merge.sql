-- Each merge of a person into another on a sheet, so the last one into a person can be undone: who was
-- merged into whom, by which user on which sheet-today, the aliases the merged person had (newline
-- separated, in the order they were added) and the ids of the claims that moved with them (comma
-- separated). Undoing a merge removes its row. People are not references, as for person.merged_into.
CREATE TABLE person_merge (
    id        BIGINT AUTO_INCREMENT PRIMARY KEY,
    sheet_id  VARCHAR(100)   NOT NULL REFERENCES sheet (id),
    from_id   BIGINT         NOT NULL,
    into_id   BIGINT         NOT NULL,
    merged_by VARCHAR(100)   NOT NULL,
    merged_on DATE           NOT NULL,
    aliases   VARCHAR(100000) NOT NULL,
    claim_ids VARCHAR(100000) NOT NULL
);
CREATE INDEX person_merge_into ON person_merge (sheet_id, into_id);
