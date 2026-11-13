-- What the screen shows as of a day: every change kept with the sheet's today when it was made, so
-- the whole sheet can be read back as it stood at the end of a past day.

-- The rest of a claim's state, kept with each state it was saved in; complete is false on the rows
-- kept before these were (their values are read from the claim as it is now).
ALTER TABLE claim_history ADD COLUMN claim_number VARCHAR(255);
ALTER TABLE claim_history ADD COLUMN opened_by VARCHAR(200);
ALTER TABLE claim_history ADD COLUMN note VARCHAR(2000);
ALTER TABLE claim_history ADD COLUMN proofs INT;
ALTER TABLE claim_history ADD COLUMN split_from VARCHAR(255);
ALTER TABLE claim_history ADD COLUMN late_entry BOOLEAN;
ALTER TABLE claim_history ADD COLUMN complete BOOLEAN DEFAULT FALSE NOT NULL;

-- Each state a person was saved in (name, terms, retirement), as of the sheet's today then.
CREATE TABLE person_history (
    id BIGINT AUTO_INCREMENT PRIMARY KEY,
    person_id BIGINT NOT NULL REFERENCES person (id),
    sheet_id VARCHAR(200) NOT NULL REFERENCES sheet (id),
    name VARCHAR(200) NOT NULL,
    terms INT,
    retired_from DATE,
    made_on DATE NOT NULL
);
CREATE INDEX person_history_sheet ON person_history (sheet_id, person_id, id);

-- The sheet's today when the statement was taken (taken_on is the day it was taken as of); null
-- for one taken before this was kept.
ALTER TABLE statement ADD COLUMN made_on DATE;

-- The sheet's today when the patron was named; null for one named before this was kept.
ALTER TABLE person_patron ADD COLUMN made_on DATE;

-- Each time a user started or stopped watching a person, as of the sheet's today then.
CREATE TABLE person_watch_history (
    id BIGINT AUTO_INCREMENT PRIMARY KEY,
    sheet_id VARCHAR(200) NOT NULL REFERENCES sheet (id),
    person_id BIGINT NOT NULL REFERENCES person (id),
    user_name VARCHAR(200) NOT NULL,
    watched BOOLEAN NOT NULL,
    made_on DATE NOT NULL
);
CREATE INDEX person_watch_history_sheet ON person_watch_history (sheet_id, user_name, id);

-- Each state a period was saved in, as of the sheet's today then; person_totals is each person's
-- fixed due-total as person-id=total pairs separated by commas (blank while it is open).
CREATE TABLE period_history (
    id BIGINT AUTO_INCREMENT PRIMARY KEY,
    period_id BIGINT NOT NULL REFERENCES period (id),
    sheet_id VARCHAR(200) NOT NULL REFERENCES sheet (id),
    name VARCHAR(200) NOT NULL,
    from_date DATE NOT NULL,
    to_date DATE NOT NULL,
    closed BOOLEAN NOT NULL,
    soft_closed BOOLEAN NOT NULL,
    closed_total DECIMAL(12, 2),
    person_totals VARCHAR(100000) NOT NULL,
    made_on DATE NOT NULL
);
CREATE INDEX period_history_sheet ON period_history (sheet_id, period_id, id);

-- Each value set for a sheet setting that is not dated (a role, a permission, the period numbering
-- from, the grace days), by key, as of the sheet's today then; null for blank.
CREATE TABLE sheet_setting_history (
    id BIGINT AUTO_INCREMENT PRIMARY KEY,
    sheet_id VARCHAR(200) NOT NULL REFERENCES sheet (id),
    setting_key VARCHAR(300) NOT NULL,
    setting_value VARCHAR(500),
    made_on DATE NOT NULL
);
CREATE INDEX sheet_setting_history_key ON sheet_setting_history (sheet_id, setting_key, id);

-- The sheet's today when the steward role was delegated; null for one made before this was kept.
ALTER TABLE delegation ADD COLUMN made_on DATE;
