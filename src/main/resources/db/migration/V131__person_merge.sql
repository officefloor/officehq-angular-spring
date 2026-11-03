-- Each merge of one person into another: who merged them and on which sheet's today, the merged
-- person as they were (grades, holds, past retirements, dated terms and aliases kept as a person's
-- revision keeps them) and the claims that came from them, so the merge can be undone. No foreign
-- keys: the merged person has left the sheet, and the other may later leave it too.
CREATE TABLE person_merge (
    id          BIGINT AUTO_INCREMENT PRIMARY KEY,
    sheet_id    VARCHAR(100)  NOT NULL REFERENCES sheet (id),
    person_id   BIGINT        NOT NULL,
    into_id     BIGINT        NOT NULL,
    merged_by   VARCHAR(100)  NOT NULL,
    merged_on   DATE          NOT NULL,
    undone      BOOLEAN       DEFAULT FALSE NOT NULL,
    name        VARCHAR(200)  NOT NULL,
    grades      VARCHAR(4000) NOT NULL,
    holds       VARCHAR(4000) NOT NULL,
    retirements VARCHAR(4000) NOT NULL,
    terms       INT,
    terms_from  VARCHAR(4000) NOT NULL,
    contact     VARCHAR(200),
    tags        VARCHAR(1000),
    aliases     VARCHAR(4000) NOT NULL,
    origin      VARCHAR(100),
    arrived_on  DATE,
    flag_reason VARCHAR(200),
    flag_until  DATE,
    approver    VARCHAR(100)
);
CREATE INDEX person_merge_into ON person_merge (sheet_id, into_id, id);
CREATE TABLE person_merge_claim (
    merge_id BIGINT NOT NULL REFERENCES person_merge (id),
    claim_id BIGINT NOT NULL,
    PRIMARY KEY (merge_id, claim_id)
);
