-- A claim's tags: trimmed, lower-cased, without repeats, sorted and comma separated; null when it has
-- none. sheet_tag keeps every tag ever used on a sheet's claims.
ALTER TABLE claim ADD COLUMN tags VARCHAR(1000);
CREATE TABLE sheet_tag (
    sheet_id VARCHAR(100) NOT NULL,
    tag      VARCHAR(500) NOT NULL,
    PRIMARY KEY (sheet_id, tag)
);
