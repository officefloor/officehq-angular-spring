-- A steward delegating the steward role to a user name for an inclusive range of the sheet's today:
-- while the sheet's today is inside it, the user acts as a steward, on behalf of the steward.
CREATE TABLE steward_delegation (
    id        BIGINT AUTO_INCREMENT PRIMARY KEY,
    sheet_id  VARCHAR(64)  NOT NULL REFERENCES sheet (id),
    user_name VARCHAR(200) NOT NULL,
    steward   VARCHAR(200) NOT NULL,
    from_date DATE         NOT NULL,
    to_date   DATE         NOT NULL,
    CHECK (to_date >= from_date)
);
CREATE INDEX steward_delegation_user ON steward_delegation (sheet_id, user_name);
