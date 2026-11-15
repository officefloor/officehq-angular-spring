-- A merge by transfer joins the arriving person's name and aliases to the aliases of the person
-- they are merged into, so one person may hold aliases differing only in case (Addie and addie).
-- The service still keeps an alias from equalling another name or alias on the sheet.
DROP INDEX person_alias_key;
