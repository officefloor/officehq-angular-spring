-- A claim may carry tags: lower case, trimmed, no repeats, kept sorted and comma separated (no
-- spaces); null when the claim has none. Every tag ever used on a sheet is kept on its list, so the
-- sheet shows a due-total for it even once no claim carries it.
ALTER TABLE claim ADD COLUMN tags VARCHAR(2000);
CREATE TABLE sheet_tag (
    sheet_id VARCHAR(200) NOT NULL REFERENCES sheet (id),
    tag VARCHAR(255) NOT NULL,
    PRIMARY KEY (sheet_id, tag)
);
