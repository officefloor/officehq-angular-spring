-- A claim's comments, in the order they were written (position 0 the oldest), each with who wrote it
-- and the sheet's today then.
CREATE TABLE claim_comment (
    claim_id BIGINT         NOT NULL REFERENCES claim (id),
    position INT            NOT NULL,
    by_user  VARCHAR(200)   NOT NULL,
    on_date  DATE           NOT NULL,
    text     VARCHAR(1000)  NOT NULL,
    PRIMARY KEY (claim_id, position)
);
