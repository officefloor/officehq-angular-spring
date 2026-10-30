-- A steward's delegation of the steward role to a user name, from a from-date to a to-date inclusive.
CREATE TABLE delegation (
    id           BIGINT AUTO_INCREMENT PRIMARY KEY,
    sheet_id     VARCHAR(100) NOT NULL REFERENCES sheet (id),
    user_name    VARCHAR(200) NOT NULL,
    from_date    DATE         NOT NULL,
    to_date      DATE         NOT NULL,
    delegated_by VARCHAR(200) NOT NULL,
    CONSTRAINT delegation_dates CHECK (to_date >= from_date)
);
