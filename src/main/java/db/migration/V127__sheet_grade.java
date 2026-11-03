package db.migration;

import java.sql.Connection;
import java.sql.ResultSet;
import java.sql.Statement;
import java.util.ArrayList;
import java.util.List;
import org.flywaydb.core.api.migration.BaseJavaMigration;
import org.flywaydb.core.api.migration.Context;

/**
 * Grades can be added to a sheet: each is a code of one to three capital letters, kept in
 * {@code sheet_grade} beside A, B and C, which every sheet has. The grade columns no longer hold
 * only A, B or C: their checks go and they widen to three letters.
 */
public class V127__sheet_grade extends BaseJavaMigration {

    private static final String[][] GRADE_COLUMNS = {
            {"PERSON_GRADE", "GRADE"}, {"RATE", "GRADE"}, {"RETIRED_GRADE", "GRADE"},
            {"RATE_REVISION", "GRADE"}, {"SHEET", "DEFAULT_GRADE"}};

    @Override
    public void migrate(Context context) throws Exception {
        Connection db = context.getConnection();
        List<String[]> checks = new ArrayList<>();
        try (Statement st = db.createStatement();
                ResultSet rs = st.executeQuery("SELECT tc.TABLE_NAME, tc.CONSTRAINT_NAME FROM"
                        + " INFORMATION_SCHEMA.TABLE_CONSTRAINTS tc JOIN INFORMATION_SCHEMA.CHECK_CONSTRAINTS cc"
                        + " ON cc.CONSTRAINT_SCHEMA = tc.CONSTRAINT_SCHEMA AND cc.CONSTRAINT_NAME = tc.CONSTRAINT_NAME"
                        + " WHERE tc.CONSTRAINT_TYPE = 'CHECK' AND tc.TABLE_SCHEMA = SCHEMA()"
                        + " AND tc.TABLE_NAME IN ('PERSON_GRADE', 'RATE', 'RETIRED_GRADE')"
                        + " AND UPPER(cc.CHECK_CLAUSE) LIKE '%GRADE%'")) {
            while (rs.next()) {
                checks.add(new String[] {rs.getString(1), rs.getString(2)});
            }
        }
        try (Statement st = db.createStatement()) {
            for (String[] check : checks) {
                st.execute("ALTER TABLE \"" + check[0] + "\" DROP CONSTRAINT \"" + check[1] + "\"");
            }
            for (String[] column : GRADE_COLUMNS) {
                st.execute("ALTER TABLE " + column[0] + " ALTER COLUMN " + column[1] + " SET DATA TYPE VARCHAR(3)");
            }
            st.execute("CREATE TABLE sheet_grade ("
                    + " id       BIGINT AUTO_INCREMENT PRIMARY KEY,"
                    + " sheet_id VARCHAR(100) NOT NULL REFERENCES sheet (id),"
                    + " grade    VARCHAR(3)   NOT NULL CHECK (REGEXP_LIKE(grade, '^[A-Z]{1,3}$')),"
                    + " added_on DATE         NOT NULL,"
                    + " CONSTRAINT sheet_grade_unique UNIQUE (sheet_id, grade))");
        }
    }
}
