package db.migration;

import java.sql.ResultSet;
import java.sql.Statement;
import java.util.ArrayList;
import java.util.List;
import org.flywaydb.core.api.migration.BaseJavaMigration;
import org.flywaydb.core.api.migration.Context;

/**
 * A grade is no longer only A, B or C but any code of one to three capital letters the sheet has
 * (see V119): the grade columns are widened and the checks that held them to A, B or C dropped.
 */
public class V120__grade_codes extends BaseJavaMigration {

    private static final List<String> TABLES = List.of("PERSON_GRADE", "RATE", "RATE_LOG", "RATE_VERSION");

    @Override
    public void migrate(Context context) throws Exception {
        List<String[]> checks = new ArrayList<>();
        try (Statement select = context.getConnection().createStatement();
                ResultSet rs = select.executeQuery("SELECT tc.TABLE_NAME, tc.CONSTRAINT_NAME, cc.CHECK_CLAUSE"
                        + " FROM INFORMATION_SCHEMA.TABLE_CONSTRAINTS tc JOIN INFORMATION_SCHEMA.CHECK_CONSTRAINTS cc"
                        + " ON cc.CONSTRAINT_SCHEMA = tc.CONSTRAINT_SCHEMA AND cc.CONSTRAINT_NAME = tc.CONSTRAINT_NAME"
                        + " WHERE tc.CONSTRAINT_TYPE = 'CHECK' AND tc.TABLE_SCHEMA = SCHEMA()")) {
            while (rs.next()) {
                if (TABLES.contains(rs.getString(1)) && rs.getString(3).toUpperCase().contains("GRADE")) {
                    checks.add(new String[] { rs.getString(1), rs.getString(2) });
                }
            }
        }
        try (Statement alter = context.getConnection().createStatement()) {
            for (String[] check : checks) {
                alter.execute("ALTER TABLE " + check[0] + " DROP CONSTRAINT \"" + check[1] + "\"");
            }
            for (String table : TABLES) {
                alter.execute("ALTER TABLE " + table + " ALTER COLUMN grade SET DATA TYPE VARCHAR(3)");
            }
        }
    }
}
