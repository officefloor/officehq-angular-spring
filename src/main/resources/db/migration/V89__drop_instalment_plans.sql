-- Instalment plans are gone: every invoice is due in full on its due date.
ALTER TABLE invoice DROP COLUMN instalments;
ALTER TABLE invoice DROP COLUMN instalment_rate;
ALTER TABLE invoice DROP COLUMN interest_saved;
ALTER TABLE invoice DROP COLUMN plan_down_pct;
ALTER TABLE invoice DROP COLUMN skipped_instalment;
ALTER TABLE invoice DROP COLUMN plan_fee;
DELETE FROM app_setting WHERE name = 'plan_fee';
