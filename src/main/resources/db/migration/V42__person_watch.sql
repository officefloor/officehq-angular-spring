-- The users watching a person, each at most once. A watcher is told (a NOTIFY record) whenever a claim of the person
-- is settled, approved or re-settled; watchers are told in the order they started watching.
CREATE TABLE person_watch (
    id        BIGINT AUTO_INCREMENT PRIMARY KEY,
    sheet_id  VARCHAR(100) NOT NULL REFERENCES sheet (id),
    person_id BIGINT       NOT NULL REFERENCES person (id),
    user_name VARCHAR(100) NOT NULL
);
CREATE UNIQUE INDEX person_watch_pair ON person_watch (person_id, user_name);
CREATE INDEX person_watch_sheet ON person_watch (sheet_id, user_name);
