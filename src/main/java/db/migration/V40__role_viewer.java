package db.migration;

import java.sql.Connection;
import java.sql.ResultSet;
import java.sql.Statement;
import java.util.ArrayList;
import java.util.List;
import org.flywaydb.core.api.migration.BaseJavaMigration;
import org.flywaydb.core.api.migration.Context;

/**
 * A user name can be given the viewer role, who sees the sheet and changes nothing, so the sheet_role table's role
 * check allows it. The old check was created unnamed, so it is found by its table.
 */
public class V40__role_viewer extends BaseJavaMigration {

    @Override
    public void migrate(Context context) throws Exception {
        Connection connection = context.getConnection();
        List<String> checks = new ArrayList<>();
        try (Statement statement = connection.createStatement();
                ResultSet rs = statement.executeQuery("SELECT tc.CONSTRAINT_NAME FROM INFORMATION_SCHEMA.TABLE_CONSTRAINTS tc"
                        + " JOIN INFORMATION_SCHEMA.CHECK_CONSTRAINTS cc ON cc.CONSTRAINT_SCHEMA = tc.CONSTRAINT_SCHEMA"
                        + " AND cc.CONSTRAINT_NAME = tc.CONSTRAINT_NAME"
                        + " WHERE tc.TABLE_NAME = 'SHEET_ROLE' AND tc.CONSTRAINT_TYPE = 'CHECK'"
                        + " AND UPPER(cc.CHECK_CLAUSE) LIKE '%ROLE%'")) {
            while (rs.next()) {
                checks.add(rs.getString(1));
            }
        }
        try (Statement statement = connection.createStatement()) {
            for (String check : checks) {
                statement.execute("ALTER TABLE sheet_role DROP CONSTRAINT \"" + check + "\"");
            }
            statement.execute("ALTER TABLE sheet_role ADD CONSTRAINT sheet_role_known"
                    + " CHECK (role IN ('clerk', 'steward', 'viewer'))");
        }
    }
}
