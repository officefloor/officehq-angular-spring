package db.migration;

import java.sql.Connection;
import java.sql.ResultSet;
import java.sql.Statement;
import java.util.ArrayList;
import java.util.List;
import org.flywaydb.core.api.migration.BaseJavaMigration;
import org.flywaydb.core.api.migration.Context;

/**
 * Grades can be added to a sheet: a code of one to three capital letters on top of A, B and C, kept on the
 * sheet comma separated in the order added. The grade columns no longer hold only A, B or C, so their checks
 * are dropped (an unnamed check has a generated name, looked up here) and they are widened to three letters.
 */
public class V121__sheet_added_grades extends BaseJavaMigration {

    @Override
    public void migrate(Context context) throws Exception {
        Connection connection = context.getConnection();
        List<String[]> checks = new ArrayList<>();
        try (Statement st = connection.createStatement();
                ResultSet rs = st.executeQuery("SELECT tc.TABLE_NAME, tc.CONSTRAINT_NAME, cc.CHECK_CLAUSE"
                        + " FROM INFORMATION_SCHEMA.TABLE_CONSTRAINTS tc"
                        + " JOIN INFORMATION_SCHEMA.CHECK_CONSTRAINTS cc"
                        + " ON cc.CONSTRAINT_SCHEMA = tc.CONSTRAINT_SCHEMA AND cc.CONSTRAINT_NAME = tc.CONSTRAINT_NAME"
                        + " WHERE tc.CONSTRAINT_TYPE = 'CHECK' AND tc.TABLE_SCHEMA = SCHEMA()"
                        + " AND tc.TABLE_NAME IN ('RATE', 'PERSON_GRADE', 'SHEET')")) {
            while (rs.next()) {
                if (rs.getString(3).toUpperCase().contains("GRADE")) {
                    checks.add(new String[] { rs.getString(1), rs.getString(2) });
                }
            }
        }
        try (Statement st = connection.createStatement()) {
            for (String[] check : checks) {
                st.execute("ALTER TABLE " + check[0] + " DROP CONSTRAINT \"" + check[1] + "\"");
            }
            st.execute("ALTER TABLE rate ALTER COLUMN grade SET DATA TYPE VARCHAR(3)");
            st.execute("ALTER TABLE person_grade ALTER COLUMN grade SET DATA TYPE VARCHAR(3)");
            st.execute("ALTER TABLE sheet ALTER COLUMN default_grade SET DATA TYPE VARCHAR(3)");
            st.execute("ALTER TABLE sheet ALTER COLUMN retired_grades SET DATA TYPE VARCHAR(2000)");
            st.execute("ALTER TABLE sheet ADD COLUMN added_grades VARCHAR(2000)");
        }
    }
}
