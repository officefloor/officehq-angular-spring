package db.migration;

import java.sql.ResultSet;
import java.sql.Statement;
import org.flywaydb.core.api.migration.BaseJavaMigration;
import org.flywaydb.core.api.migration.Context;

/**
 * A claim's place in its sheet's list: claims are listed by it, newest first. A claim added takes
 * the next from claim_listed_seq, and so does one moved to another person, which is then listed as
 * their newest. Existing claims keep their order by id; the sequence starts after the last of them.
 */
public class V29__claim_listed extends BaseJavaMigration {

    @Override
    public void migrate(Context context) throws Exception {
        long last;
        try (Statement query = context.getConnection().createStatement();
                ResultSet rows = query.executeQuery("SELECT COALESCE(MAX(id), 0) FROM claim")) {
            rows.next();
            last = rows.getLong(1);
        }
        try (Statement alter = context.getConnection().createStatement()) {
            alter.execute("CREATE SEQUENCE claim_listed_seq START WITH " + (last + 1));
            alter.execute("ALTER TABLE claim ADD COLUMN listed BIGINT");
            alter.execute("UPDATE claim SET listed = id");
            alter.execute("ALTER TABLE claim ALTER COLUMN listed SET DEFAULT NEXT VALUE FOR claim_listed_seq");
            alter.execute("ALTER TABLE claim ALTER COLUMN listed SET NOT NULL");
            alter.execute("CREATE INDEX claim_sheet_listed ON claim (sheet_id, listed)");
        }
    }
}
