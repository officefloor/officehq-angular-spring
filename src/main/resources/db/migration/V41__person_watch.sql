-- A user watching a person on a sheet: when one of the person's claims is settled, approved or
-- re-settled, each watcher is notified, in the order they started watching (by id). A user watches
-- a person at most once; stopping removes the row, so watching again starts a new place in order.
CREATE TABLE person_watch (
    id        BIGINT AUTO_INCREMENT PRIMARY KEY,
    sheet_id  VARCHAR(64)  NOT NULL REFERENCES sheet (id),
    person_id BIGINT       NOT NULL REFERENCES person (id),
    user_name VARCHAR(200) NOT NULL,
    UNIQUE (person_id, user_name)
);
CREATE INDEX person_watch_sheet ON person_watch (sheet_id, user_name);
