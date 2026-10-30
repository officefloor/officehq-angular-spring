-- A claim's tags: trimmed, lower-cased, without repeats, sorted and comma separated; '' when it has none.
ALTER TABLE claim ADD COLUMN tags VARCHAR(1000) NOT NULL DEFAULT '';

-- Every tag ever used on a sheet, each once: the sheet shows a due-total for each.
CREATE TABLE sheet_tag (
    id       BIGINT AUTO_INCREMENT PRIMARY KEY,
    sheet_id VARCHAR(100) NOT NULL REFERENCES sheet (id),
    tag      VARCHAR(100) NOT NULL,
    CONSTRAINT sheet_tag_unique UNIQUE (sheet_id, tag)
);
