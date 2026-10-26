-- A claim's tags: trimmed, lower-cased, without repeats, sorted and comma separated; null for a claim with none.
ALTER TABLE claim ADD COLUMN tags VARCHAR(1000);

-- Every tag ever used on a claim of the sheet, each at most once: the sheet shows a due-total for each.
CREATE TABLE claim_tag (
    id       BIGINT AUTO_INCREMENT PRIMARY KEY,
    sheet_id VARCHAR(100) NOT NULL REFERENCES sheet (id),
    tag      VARCHAR(140) NOT NULL
);
CREATE UNIQUE INDEX claim_tag_once ON claim_tag (sheet_id, tag);
