-- The sheet's today a snapshot was taken into (the new today the day change moved to): an as-of view
-- shows only the snapshots taken by then. None for those kept before; their day being left stands in.
ALTER TABLE person_snapshot ADD COLUMN taken_on DATE;
