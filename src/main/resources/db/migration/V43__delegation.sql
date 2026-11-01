-- A steward's role delegated to a user name on a sheet for a from-date and a to-date, inclusive; the id
-- orders them by when they were made.
CREATE TABLE delegation (
    id         BIGINT AUTO_INCREMENT PRIMARY KEY,
    sheet_id   VARCHAR(100) NOT NULL REFERENCES sheet (id),
    user_name  VARCHAR(100) NOT NULL,
    delegator  VARCHAR(100) NOT NULL,
    from_date  DATE         NOT NULL,
    to_date    DATE         NOT NULL
);
CREATE INDEX delegation_sheet_user ON delegation (sheet_id, user_name);
