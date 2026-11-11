-- A claim's comments, in the order of their ids. Each keeps who wrote it, the sheet's today then, and its text.
CREATE TABLE claim_comment (
    id         BIGINT AUTO_INCREMENT PRIMARY KEY,
    sheet_id   VARCHAR(100)  NOT NULL REFERENCES sheet (id),
    claim_id   BIGINT        NOT NULL REFERENCES claim (id),
    by_user    VARCHAR(100)  NOT NULL,
    written_on DATE          NOT NULL,
    text       VARCHAR(1000) NOT NULL
);
CREATE INDEX claim_comment_sheet ON claim_comment (sheet_id, id);
