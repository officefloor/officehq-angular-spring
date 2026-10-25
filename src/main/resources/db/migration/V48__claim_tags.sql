-- A claim may carry tags: lower-cased, sorted and comma separated; null when it has none.
ALTER TABLE claim ADD COLUMN tags VARCHAR(1000);
-- Every tag ever used on a sheet, each once, kept when no claim carries it any more.
CREATE TABLE sheet_tag (
    id       BIGINT AUTO_INCREMENT PRIMARY KEY,
    sheet_id VARCHAR(100) NOT NULL REFERENCES sheet (id),
    name     VARCHAR(220) NOT NULL
);
CREATE UNIQUE INDEX sheet_tag_once ON sheet_tag (sheet_id, name);
