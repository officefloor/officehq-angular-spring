-- A person merged into another is kept, naming the person they were merged into, and leaves the
-- sheet: no list, figure or lookup includes them. Not a reference, so the sheet's history can put
-- its people back in any order.
ALTER TABLE person ADD COLUMN merged_into BIGINT;
