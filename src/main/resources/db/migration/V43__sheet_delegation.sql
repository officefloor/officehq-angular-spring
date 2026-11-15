-- A steward's role delegated to a user name for a from-date to a to-date, inclusive, by the
-- sheet's today; while the sheet's today is inside the range the user acts as a steward.
CREATE TABLE sheet_delegation (
    id         BIGINT AUTO_INCREMENT PRIMARY KEY,
    sheet_id   VARCHAR(100) NOT NULL REFERENCES sheet (id),
    user_name  VARCHAR(100) NOT NULL,
    steward    VARCHAR(100) NOT NULL,
    from_date  DATE         NOT NULL,
    to_date    DATE         NOT NULL,
    CHECK (to_date >= from_date)
);
CREATE INDEX sheet_delegation_user ON sheet_delegation (sheet_id, user_name);
