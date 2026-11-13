-- A steward's delegation of the steward role to a user for a from-date to a to-date, inclusive, by
-- the sheet's today: while the today is inside the range the user acts as a steward.
CREATE TABLE delegation (
    id BIGINT AUTO_INCREMENT PRIMARY KEY,
    sheet_id VARCHAR(200) NOT NULL REFERENCES sheet (id),
    user_name VARCHAR(200) NOT NULL,
    from_date DATE NOT NULL,
    to_date DATE NOT NULL,
    delegated_by VARCHAR(200) NOT NULL,
    CHECK (to_date >= from_date)
);
CREATE INDEX delegation_sheet_user ON delegation (sheet_id, user_name);
