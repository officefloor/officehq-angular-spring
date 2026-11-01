-- A claim may carry tags: trimmed, lower-cased, each once, kept sorted and comma separated; empty for none.
ALTER TABLE claim ADD COLUMN tags VARCHAR(2000) NOT NULL DEFAULT '';
-- Every tag ever used on a claim of the sheet, each once; the sheet shows a due-total for each.
CREATE TABLE claim_tag (
    id       BIGINT AUTO_INCREMENT PRIMARY KEY,
    sheet_id VARCHAR(100) NOT NULL REFERENCES sheet (id),
    tag      VARCHAR(200) NOT NULL
);
CREATE UNIQUE INDEX claim_tag_unique ON claim_tag (sheet_id, tag);
