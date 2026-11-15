package db.migration;

import java.sql.Connection;
import java.sql.ResultSet;
import java.sql.Statement;
import java.util.ArrayList;
import java.util.List;
import org.flywaydb.core.api.migration.BaseJavaMigration;
import org.flywaydb.core.api.migration.Context;

/**
 * Grades can be added to a sheet: a code of one to three capital letters, kept on the sheet after
 * the grades every sheet has (A, B, C). The grade columns no longer hold only A, B or C, and are
 * wide enough for a code of three letters; the columns holding grades in a list, or a setting key
 * followed by a grade, are widened to match.
 */
public class V117__grade_added extends BaseJavaMigration {

    @Override
    public void migrate(Context context) throws Exception {
        Connection connection = context.getConnection();
        List<String> drops = new ArrayList<>();
        try (Statement select = connection.createStatement();
                ResultSet rs = select.executeQuery("SELECT tc.TABLE_NAME, tc.CONSTRAINT_NAME"
                        + " FROM INFORMATION_SCHEMA.TABLE_CONSTRAINTS tc"
                        + " JOIN INFORMATION_SCHEMA.CHECK_CONSTRAINTS cc"
                        + " ON cc.CONSTRAINT_SCHEMA = tc.CONSTRAINT_SCHEMA AND cc.CONSTRAINT_NAME = tc.CONSTRAINT_NAME"
                        + " WHERE tc.CONSTRAINT_TYPE = 'CHECK' AND tc.TABLE_NAME IN ('PERSON', 'RATE', 'PERSON_GRADE')"
                        + " AND UPPER(cc.CHECK_CLAUSE) LIKE '%GRADE%'")) {
            while (rs.next()) {
                drops.add("ALTER TABLE " + rs.getString(1) + " DROP CONSTRAINT \"" + rs.getString(2) + "\"");
            }
        }
        try (Statement alter = connection.createStatement()) {
            for (String drop : drops) {
                alter.execute(drop);
            }
            alter.execute("ALTER TABLE person ALTER COLUMN grade SET DATA TYPE VARCHAR(3)");
            alter.execute("ALTER TABLE rate ALTER COLUMN grade SET DATA TYPE VARCHAR(3)");
            alter.execute("ALTER TABLE person_grade ALTER COLUMN grade SET DATA TYPE VARCHAR(3)");
            alter.execute("ALTER TABLE sheet ALTER COLUMN default_grade SET DATA TYPE VARCHAR(3)");
            alter.execute("ALTER TABLE sheet ALTER COLUMN retired_grades SET DATA TYPE VARCHAR(4000)");
            alter.execute("ALTER TABLE sheet_setting ALTER COLUMN setting_key SET DATA TYPE VARCHAR(40)");
            // The grades added to the sheet, comma separated in the order added; null when none.
            alter.execute("ALTER TABLE sheet ADD COLUMN added_grades VARCHAR(4000)");
        }
    }
}
