-- A closed or soft-closed period fixes every pool member's cut for it as it stood when it was
-- closed: the last version of the sheet's history then (its pools, crews, pot and people) and the
-- sheet's today then, the day the cuts are taken on. Both null while open (the cuts are worked as
-- they stood at the end of its to-date).
ALTER TABLE period ADD COLUMN cuts_at BIGINT;
ALTER TABLE period ADD COLUMN cuts_on DATE;
