-- A steward's delegation of the steward role to a user name for a from-date to a to-date, inclusive;
-- the id keeps the order they were made.
CREATE TABLE delegation (
    id           BIGINT AUTO_INCREMENT PRIMARY KEY,
    sheet_id     VARCHAR(100) NOT NULL REFERENCES sheet (id),
    user_name    VARCHAR(200) NOT NULL,
    from_date    DATE         NOT NULL,
    to_date      DATE         NOT NULL,
    delegated_by VARCHAR(255) NOT NULL,
    CHECK (to_date >= from_date)
);
CREATE INDEX delegation_sheet ON delegation (sheet_id, user_name);
