-- The void reasons on a sheet: while its list is not empty, a claim is voided with one of them. Each reason is on a
-- sheet's list at most once. Ids rise in the order reasons were added.
CREATE TABLE void_reason (
    id       BIGINT AUTO_INCREMENT PRIMARY KEY,
    sheet_id VARCHAR(100) NOT NULL REFERENCES sheet (id),
    reason   VARCHAR(200) NOT NULL
);
CREATE UNIQUE INDEX void_reason_sheet_reason ON void_reason (sheet_id, reason);

-- The reason from its sheet's list a claim was voided with; none when it is not void or was voided without one.
ALTER TABLE claim ADD COLUMN void_reason VARCHAR(200);
