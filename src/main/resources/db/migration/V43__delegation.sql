-- A steward's delegation of the steward role to a user for a from-date to a to-date, inclusive: while the sheet's
-- today is inside the range the user acts as a steward, and each change the user makes is recorded for the steward.
CREATE TABLE delegation (
    id           BIGINT AUTO_INCREMENT PRIMARY KEY,
    sheet_id     VARCHAR(100) NOT NULL REFERENCES sheet (id),
    user_name    VARCHAR(100) NOT NULL,
    delegated_by VARCHAR(100) NOT NULL,
    from_date    DATE         NOT NULL,
    to_date      DATE         NOT NULL
);
CREATE INDEX delegation_user ON delegation (sheet_id, user_name);
