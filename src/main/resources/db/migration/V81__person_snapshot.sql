-- A person's snapshot lines: one per SNAPSHOT written for them when the sheet's today moved, the day
-- being left and their due-total then (null: n/a). pos orders them, oldest first.
CREATE TABLE person_snapshot (
    person_id BIGINT        NOT NULL REFERENCES person (id),
    pos       INT           NOT NULL,
    left_on   DATE          NOT NULL,
    due_total DECIMAL(19, 2)
);
CREATE INDEX person_snapshot_person ON person_snapshot (person_id);
