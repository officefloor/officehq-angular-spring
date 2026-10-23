-- Whether the figure a statement bound the claim to is its whole due as shown, late charge and floor
-- included (so neither follows the sheet's today while it is bound). Claims bound before this were
-- bound to their rate figure alone, the late charge and floor staying live on top.
ALTER TABLE claim ADD COLUMN stated_whole BOOLEAN NOT NULL DEFAULT FALSE;
