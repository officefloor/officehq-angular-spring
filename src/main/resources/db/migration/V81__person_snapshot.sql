-- A person's snapshot lines: one written each time the sheet's today moves, with the day being
-- left and the person's due-total on it (two decimals; NULL when it was n/a), oldest first.
CREATE TABLE person_snapshot (
    person_id BIGINT        NOT NULL REFERENCES person (id),
    position  INT           NOT NULL,
    snapshot_day DATE       NOT NULL,
    due_total DECIMAL(15, 2),
    PRIMARY KEY (person_id, position)
);
