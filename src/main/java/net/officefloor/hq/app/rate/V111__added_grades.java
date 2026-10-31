package net.officefloor.hq.app.rate;

import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;
import java.util.ArrayList;
import java.util.List;
import org.flywaydb.core.api.migration.BaseJavaMigration;
import org.flywaydb.core.api.migration.Context;
import org.springframework.stereotype.Component;

/**
 * Grades can be added to a sheet: a code of one to three capital letters, kept per sheet in the order added
 * (after A, B and C, which every sheet has). The grade columns take three letters and no longer hold only A, B
 * or C: the unnamed checks limiting them to those are found and dropped.
 */
@Component
public class V111__added_grades extends BaseJavaMigration {

    private static final List<String> GRADE_TABLES = List.of("RATE", "PERSON_GRADE");

    @Override
    public void migrate(Context context) throws SQLException {
        Connection db = context.getConnection();
        List<String[]> checks = new ArrayList<>();
        try (PreparedStatement select = db.prepareStatement("SELECT tc.TABLE_NAME, tc.CONSTRAINT_NAME, cc.CHECK_CLAUSE"
                + " FROM INFORMATION_SCHEMA.TABLE_CONSTRAINTS tc JOIN INFORMATION_SCHEMA.CHECK_CONSTRAINTS cc"
                + " ON cc.CONSTRAINT_SCHEMA = tc.CONSTRAINT_SCHEMA AND cc.CONSTRAINT_NAME = tc.CONSTRAINT_NAME"
                + " WHERE tc.CONSTRAINT_TYPE = 'CHECK' AND tc.TABLE_SCHEMA = SCHEMA()");
                ResultSet rows = select.executeQuery()) {
            while (rows.next()) {
                String clause = rows.getString(3).toUpperCase();
                if (GRADE_TABLES.contains(rows.getString(1)) && clause.contains("GRADE")) {
                    checks.add(new String[] {rows.getString(1), rows.getString(2)});
                }
            }
        }
        try (Statement ddl = db.createStatement()) {
            for (String[] check : checks) {
                ddl.execute("ALTER TABLE " + check[0] + " DROP CONSTRAINT \"" + check[1] + "\"");
            }
            ddl.execute("ALTER TABLE rate ALTER COLUMN grade VARCHAR(3) NOT NULL");
            ddl.execute("ALTER TABLE person_grade ALTER COLUMN grade VARCHAR(3) NOT NULL");
            ddl.execute("ALTER TABLE retired_grade ALTER COLUMN grade VARCHAR(3) NOT NULL");
            ddl.execute("CREATE TABLE sheet_grade ("
                    + " id BIGINT AUTO_INCREMENT PRIMARY KEY,"
                    + " sheet_id VARCHAR(100) NOT NULL REFERENCES sheet (id),"
                    + " grade VARCHAR(3) NOT NULL,"
                    + " CONSTRAINT sheet_grade_unique UNIQUE (sheet_id, grade))");
        }
    }
}
