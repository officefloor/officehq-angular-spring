-- What the sheet held as of a day: each change that bears on a claim's figures is kept with the
-- sheet's today when it was made, so a statement can be taken as of a past day (every change made
-- while the sheet's today was on or before it counts, every later one does not).

-- The sheet's today when the person was added; null for one added before this was kept.
ALTER TABLE person ADD COLUMN added_on DATE;

-- The sheet's today when the hold was put on; null for one put on before this was kept.
ALTER TABLE person_hold ADD COLUMN made_on DATE;

-- Each rate as it was set: the grade's rate from its from-date, as of the sheet's today then.
CREATE TABLE rate_history (
    id BIGINT AUTO_INCREMENT PRIMARY KEY,
    sheet_id VARCHAR(200) NOT NULL REFERENCES sheet (id),
    grade VARCHAR(1) NOT NULL,
    from_date DATE,
    to_date DATE,
    rate DECIMAL(7, 2) NOT NULL,
    made_on DATE NOT NULL
);
CREATE INDEX rate_history_sheet ON rate_history (sheet_id, grade, id);

-- Each grade a person was given from a from-date, as of the sheet's today then.
CREATE TABLE person_grade_history (
    id BIGINT AUTO_INCREMENT PRIMARY KEY,
    sheet_id VARCHAR(200) NOT NULL REFERENCES sheet (id),
    person_id BIGINT NOT NULL REFERENCES person (id),
    grade VARCHAR(1) NOT NULL,
    from_date DATE,
    made_on DATE NOT NULL
);
CREATE INDEX person_grade_history_person ON person_grade_history (person_id, id);

-- Each value recorded for a dated setting from a from-date, as of the sheet's today then.
CREATE TABLE sheet_setting_value_history (
    id BIGINT AUTO_INCREMENT PRIMARY KEY,
    sheet_id VARCHAR(200) NOT NULL REFERENCES sheet (id),
    setting_key VARCHAR(40) NOT NULL,
    from_date DATE NOT NULL,
    setting_value DECIMAL(12, 2),
    made_on DATE NOT NULL
);
CREATE INDEX sheet_setting_value_history_key ON sheet_setting_value_history (sheet_id, setting_key, id);

-- Each policy date set (null: blank), as of the sheet's today then.
CREATE TABLE sheet_policy_date_history (
    id BIGINT AUTO_INCREMENT PRIMARY KEY,
    sheet_id VARCHAR(200) NOT NULL REFERENCES sheet (id),
    policy_date DATE,
    made_on DATE NOT NULL
);
CREATE INDEX sheet_policy_date_history_sheet ON sheet_policy_date_history (sheet_id, id);

-- Each state a claim was saved in, as of the sheet's today then.
CREATE TABLE claim_history (
    id BIGINT AUTO_INCREMENT PRIMARY KEY,
    claim_id BIGINT NOT NULL REFERENCES claim (id),
    sheet_id VARCHAR(200) NOT NULL REFERENCES sheet (id),
    person_id BIGINT NOT NULL,
    amount DECIMAL(12, 2) NOT NULL,
    claim_date DATE NOT NULL,
    due_by DATE NOT NULL,
    status VARCHAR(20) NOT NULL,
    settled_due DECIMAL(12, 2),
    stated BOOLEAN NOT NULL,
    stated_due DECIMAL(12, 2),
    corrects_number VARCHAR(255),
    deferred_until DATE,
    made_on DATE NOT NULL
);
CREATE INDEX claim_history_claim ON claim_history (claim_id, id);
