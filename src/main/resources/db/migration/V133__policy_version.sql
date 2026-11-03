-- The sheet's policy versions: every change to one of its policy settings (the rounding mode, the
-- floor, the cap, the terms, the late percent, the clerk limit) is a new version, numbered from 1 per
-- sheet in the order made, with the from-date it was made from (null: none, counts always) and the
-- sheet's today when it was made.
CREATE TABLE policy_version (
    id          BIGINT AUTO_INCREMENT PRIMARY KEY,
    sheet_id    VARCHAR(100) NOT NULL REFERENCES sheet (id),
    number      INT          NOT NULL,
    setting_key VARCHAR(50)  NOT NULL,
    from_date   DATE,
    recorded_on DATE         NOT NULL,
    CONSTRAINT policy_version_number UNIQUE (sheet_id, number)
);
