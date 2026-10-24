-- What is due on a bound claim as it showed when its statement bound it, late charge and floor
-- included. A bound claim shows and totals stated_shown: its late charge no longer follows the sheet's
-- today until the binding is cancelled. Null while unbound, when it had no figure when stated, and on
-- claims bound before this was kept (their late charge follows the sheet's today on stated_due).
ALTER TABLE claim ADD COLUMN stated_shown DECIMAL(12, 2);
