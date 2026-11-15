-- A claim's tags: lower-case, sorted and comma separated, without repeats. NULL when it has none.
ALTER TABLE claim ADD COLUMN tags VARCHAR(1000);

-- Every tag ever used on a sheet, kept once a claim has carried it.
CREATE TABLE sheet_tag (
    id        BIGINT AUTO_INCREMENT PRIMARY KEY,
    sheet_id  VARCHAR(100) NOT NULL REFERENCES sheet (id),
    tag       VARCHAR(1000) NOT NULL,
    UNIQUE (sheet_id, tag)
);
