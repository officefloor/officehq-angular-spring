-- A sheet's void reasons: while its list is not empty, a claim is voided with a reason chosen from
-- it, which the claim keeps (null when it was voided with none). A reason is on a sheet's list at
-- most once.
CREATE TABLE void_reason (
    id BIGINT AUTO_INCREMENT PRIMARY KEY,
    sheet_id VARCHAR(200) NOT NULL REFERENCES sheet (id),
    reason VARCHAR(255) NOT NULL
);
CREATE UNIQUE INDEX void_reason_sheet_reason ON void_reason (sheet_id, reason);
ALTER TABLE claim ADD COLUMN void_reason VARCHAR(255);
