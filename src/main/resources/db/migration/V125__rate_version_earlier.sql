-- An older version of a rate keeps what the claims already on the sheet then used instead of it (the rate
-- row's earlier entries as they stood), so withdrawing the version that replaced it restores them.
ALTER TABLE rate_version ADD COLUMN earlier VARCHAR(1000000);
