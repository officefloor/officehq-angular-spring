-- A claim's comments, oldest first (by id): each with the user who wrote it and the sheet's today
-- when it was written.
CREATE TABLE claim_comment (
    id        BIGINT AUTO_INCREMENT PRIMARY KEY,
    sheet_id  VARCHAR(100)   NOT NULL REFERENCES sheet (id),
    claim_id  BIGINT         NOT NULL REFERENCES claim (id),
    by_user   VARCHAR(100)   NOT NULL,
    on_date   DATE           NOT NULL,
    text      VARCHAR(1000)  NOT NULL
);
CREATE INDEX claim_comment_sheet ON claim_comment (sheet_id, id);
CREATE INDEX claim_comment_claim ON claim_comment (claim_id, id);
