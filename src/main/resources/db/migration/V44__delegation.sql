-- A steward's delegation of the steward role to a user name, from a date to a date, inclusive, by the sheet's
-- today; in the order they were made.
CREATE TABLE delegation (
    id        BIGINT AUTO_INCREMENT PRIMARY KEY,
    sheet_id  VARCHAR(100) NOT NULL REFERENCES sheet (id),
    delegator VARCHAR(100) NOT NULL,
    user_name VARCHAR(100) NOT NULL,
    from_date DATE         NOT NULL,
    to_date   DATE         NOT NULL,
    CONSTRAINT delegation_range CHECK (to_date >= from_date)
);
CREATE INDEX delegation_sheet_user ON delegation (sheet_id, user_name);
