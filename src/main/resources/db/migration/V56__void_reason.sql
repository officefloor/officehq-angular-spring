-- A void reason on the sheet's list: while the list is not empty, a claim is voided only with one
-- of them. A reason is on a sheet's list at most once.
CREATE TABLE sheet_void_reason (
    id        BIGINT AUTO_INCREMENT PRIMARY KEY,
    sheet_id  VARCHAR(100) NOT NULL REFERENCES sheet (id),
    reason    VARCHAR(200) NOT NULL,
    UNIQUE (sheet_id, reason)
);

-- The reason a VOID claim was voided with, from its sheet's list; NULL when none was given.
ALTER TABLE claim ADD COLUMN void_reason VARCHAR(200);
