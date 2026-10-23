package db.migration;

import java.sql.Connection;
import java.sql.ResultSet;
import java.sql.Statement;
import java.util.ArrayList;
import java.util.List;
import org.flywaydb.core.api.migration.BaseJavaMigration;
import org.flywaydb.core.api.migration.Context;

/**
 * Grades can be added to a sheet: beside A, B and C, a sheet holds the grades added to it, each a code
 * of one to three capital letters, listed in the order they were added (by id), with who added it. A
 * grade is no longer limited to A, B or C wherever one is kept: its columns take up to three letters
 * and lose their checks (the app keeps a grade to one of its sheet's).
 */
public class V120__grade_added extends BaseJavaMigration {

    @Override
    public void migrate(Context context) throws Exception {
        Connection connection = context.getConnection();
        List<String[]> checks = new ArrayList<>();
        try (Statement query = connection.createStatement();
                ResultSet rows = query.executeQuery("""
                        SELECT tc.TABLE_NAME, tc.CONSTRAINT_NAME
                        FROM INFORMATION_SCHEMA.TABLE_CONSTRAINTS tc
                        JOIN INFORMATION_SCHEMA.CHECK_CONSTRAINTS cc
                          ON cc.CONSTRAINT_SCHEMA = tc.CONSTRAINT_SCHEMA AND cc.CONSTRAINT_NAME = tc.CONSTRAINT_NAME
                        WHERE tc.CONSTRAINT_TYPE = 'CHECK'
                          AND tc.TABLE_NAME IN ('PERSON', 'RATE', 'PERSON_GRADE')
                          AND UPPER(cc.CHECK_CLAUSE) LIKE '%GRADE%'""")) {
            while (rows.next()) {
                checks.add(new String[] { rows.getString(1), rows.getString(2) });
            }
        }
        try (Statement ddl = connection.createStatement()) {
            for (String[] check : checks) {
                ddl.execute("ALTER TABLE " + check[0] + " DROP CONSTRAINT \"" + check[1] + "\"");
            }
            ddl.execute("ALTER TABLE person ALTER COLUMN grade VARCHAR(3) NOT NULL");
            ddl.execute("ALTER TABLE person_grade ALTER COLUMN grade VARCHAR(3) NOT NULL");
            ddl.execute("ALTER TABLE rate ALTER COLUMN grade VARCHAR(3) NOT NULL");
            ddl.execute("ALTER TABLE grade_retired ALTER COLUMN grade VARCHAR(3) NOT NULL");
            ddl.execute("ALTER TABLE sheet ALTER COLUMN default_grade VARCHAR(3)");
            ddl.execute("""
                    CREATE TABLE grade_added (
                        id       BIGINT AUTO_INCREMENT PRIMARY KEY,
                        sheet_id VARCHAR(64)  NOT NULL REFERENCES sheet (id),
                        grade    VARCHAR(3)   NOT NULL CHECK (REGEXP_LIKE(grade, '^[A-Z]{1,3}$')),
                        added_by VARCHAR(200) NOT NULL
                    )""");
            ddl.execute("CREATE UNIQUE INDEX grade_added_sheet_grade ON grade_added (sheet_id, grade)");
        }
    }
}
