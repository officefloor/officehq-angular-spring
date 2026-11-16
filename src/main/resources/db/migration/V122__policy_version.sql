-- The policy settings (rounding mode, floor, cap, terms, late percent and clerk limit) are
-- versioned together: every change to one of them is a new version of the sheet's policy, numbered
-- from 1 in the order made, with the from-date the changed value is in force from (NULL: none, a
-- version counted on every date).
CREATE TABLE sheet_policy_version (
    sheet_id    VARCHAR(100) NOT NULL REFERENCES sheet (id),
    number      INT          NOT NULL,
    setting_key VARCHAR(40)  NOT NULL,
    from_date   DATE,
    PRIMARY KEY (sheet_id, number)
);
