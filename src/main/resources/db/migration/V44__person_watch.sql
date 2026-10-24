-- The people each user watches on a sheet, each at most once per user, in the order they started
-- watching: a watched person's claim settled, approved or re-settled notifies each watcher.
CREATE TABLE person_watch (
    id        BIGINT AUTO_INCREMENT PRIMARY KEY,
    sheet_id  VARCHAR(100) NOT NULL REFERENCES sheet (id),
    person_id BIGINT       NOT NULL REFERENCES person (id),
    user_name VARCHAR(100) NOT NULL,
    CONSTRAINT person_watch_unique UNIQUE (person_id, user_name)
);
CREATE INDEX person_watch_sheet ON person_watch (sheet_id, user_name);
