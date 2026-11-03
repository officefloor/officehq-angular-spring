-- Sheet-wide claim numbering restarts each fiscal year: the last number each sheet gave within a
-- fiscal year (FYyyyy). The sheets of a tree share one sequence per fiscal year, so the next is the
-- one after the largest of them over the tree.
CREATE TABLE fiscal_claim_number (
    sheet_id VARCHAR(255) NOT NULL,
    fiscal_year VARCHAR(16) NOT NULL,
    last_number INT NOT NULL,
    PRIMARY KEY (sheet_id, fiscal_year)
);
