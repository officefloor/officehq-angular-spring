-- A person's snapshot lines: one per SNAPSHOT written for them, when the sheet's today is changed, with the day being
-- left and their due-total on it (null: n/a). The id orders them, oldest first;
-- they go with the person.
CREATE TABLE person_snapshot (
    id        BIGINT AUTO_INCREMENT PRIMARY KEY,
    person_id BIGINT         NOT NULL REFERENCES person (id) ON DELETE CASCADE,
    left_day  DATE           NOT NULL,
    due_total NUMERIC(14, 2)
);
CREATE INDEX person_snapshot_person ON person_snapshot (person_id, id);
