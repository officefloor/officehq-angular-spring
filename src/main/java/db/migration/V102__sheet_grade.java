package db.migration;

import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.Statement;
import java.util.ArrayList;
import java.util.List;
import org.flywaydb.core.api.migration.BaseJavaMigration;
import org.flywaydb.core.api.migration.Context;

/**
 * Grades can be added to a sheet: every sheet has A, B and C, and the grades added to it, each a code of one
 * to three capital letters, at most once per sheet, kept in the order added. A grade column holds any such
 * code, so the checks that held it to A, B or C are dropped. The grades added are not dated: the as-of view
 * lists them at every date, so they are kept apart from the sheet's history, with no reference to the sheet
 * row it puts back.
 */
public class V102__sheet_grade extends BaseJavaMigration {

    private static final String[] GRADED = { "PERSON_GRADE", "RATE", "RATE_CHANGE", "RETIRED_GRADE" };

    @Override
    public void migrate(Context context) throws Exception {
        Connection db = context.getConnection();
        for (String table : GRADED) {
            List<String> checks = new ArrayList<>();
            try (PreparedStatement read = db.prepareStatement(
                    "SELECT tc.CONSTRAINT_NAME FROM INFORMATION_SCHEMA.TABLE_CONSTRAINTS tc"
                            + " JOIN INFORMATION_SCHEMA.CHECK_CONSTRAINTS cc"
                            + " ON cc.CONSTRAINT_SCHEMA = tc.CONSTRAINT_SCHEMA AND cc.CONSTRAINT_NAME = tc.CONSTRAINT_NAME"
                            + " WHERE tc.TABLE_SCHEMA = SCHEMA() AND tc.TABLE_NAME = ? AND tc.CONSTRAINT_TYPE = 'CHECK'"
                            + " AND UPPER(cc.CHECK_CLAUSE) LIKE '%GRADE%'")) {
                read.setString(1, table);
                try (ResultSet rows = read.executeQuery()) {
                    while (rows.next()) checks.add(rows.getString(1));
                }
            }
            try (Statement ddl = db.createStatement()) {
                for (String check : checks) ddl.execute("ALTER TABLE " + table + " DROP CONSTRAINT \"" + check + "\"");
                ddl.execute("ALTER TABLE " + table + " ALTER COLUMN grade SET DATA TYPE VARCHAR(3)");
            }
        }
        try (Statement ddl = db.createStatement()) {
            ddl.execute("CREATE TABLE sheet_grade ("
                    + " id       BIGINT AUTO_INCREMENT PRIMARY KEY,"
                    + " sheet_id VARCHAR(100) NOT NULL,"
                    + " grade    VARCHAR(3)   NOT NULL)");
            ddl.execute("CREATE UNIQUE INDEX sheet_grade_once ON sheet_grade (sheet_id, grade)");
        }
    }
}
