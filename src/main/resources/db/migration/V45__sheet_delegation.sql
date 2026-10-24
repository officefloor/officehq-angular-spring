-- The steward role delegated by a steward to a user on a sheet, from a from-date to a to-date
-- inclusive: while the sheet's today is inside the range the user acts as a steward.
CREATE TABLE sheet_delegation (
    id        BIGINT AUTO_INCREMENT PRIMARY KEY,
    sheet_id  VARCHAR(100) NOT NULL REFERENCES sheet (id),
    user_name VARCHAR(100) NOT NULL,
    delegator VARCHAR(100) NOT NULL,
    from_date DATE         NOT NULL,
    to_date   DATE         NOT NULL,
    CHECK (to_date >= from_date)
);
CREATE INDEX sheet_delegation_sheet_user ON sheet_delegation (sheet_id, user_name);
