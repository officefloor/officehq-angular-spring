-- A sheet's children are the sheets naming it as their parent: looked up to consolidate a sheet's figures.
CREATE INDEX sheet_parent_idx ON sheet (parent_id);
