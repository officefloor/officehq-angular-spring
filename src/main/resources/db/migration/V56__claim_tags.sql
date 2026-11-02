-- A claim's tags: trimmed, lower-cased, without repeats, sorted and comma separated; null when it
-- has none. Every tag ever used on a sheet is kept in sheet_tag, so the sheet shows its due-total
-- even once no claim carries it.
ALTER TABLE claim ADD COLUMN tags VARCHAR(1000);
CREATE TABLE sheet_tag (
    id       BIGINT AUTO_INCREMENT PRIMARY KEY,
    sheet_id VARCHAR(100) NOT NULL REFERENCES sheet (id),
    tag      VARCHAR(1000) NOT NULL,
    CONSTRAINT sheet_tag_unique UNIQUE (sheet_id, tag)
);
