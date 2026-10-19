package db.migration;

import java.sql.Connection;
import java.sql.ResultSet;
import java.sql.Statement;
import java.util.ArrayList;
import java.util.List;
import org.flywaydb.core.api.migration.BaseJavaMigration;
import org.flywaydb.core.api.migration.Context;

/**
 * A correction's amount is negative, so the claim table no longer requires an amount of 0.00 or more; it requires
 * instead that a negative amount names the claim it corrects. The old check was created unnamed, so it is found
 * by its table.
 */
public class V26__claim_amount_may_be_negative extends BaseJavaMigration {

    @Override
    public void migrate(Context context) throws Exception {
        Connection connection = context.getConnection();
        List<String> checks = new ArrayList<>();
        try (Statement statement = connection.createStatement();
                ResultSet rs = statement.executeQuery("SELECT tc.CONSTRAINT_NAME FROM INFORMATION_SCHEMA.TABLE_CONSTRAINTS tc"
                        + " JOIN INFORMATION_SCHEMA.CHECK_CONSTRAINTS cc ON cc.CONSTRAINT_SCHEMA = tc.CONSTRAINT_SCHEMA"
                        + " AND cc.CONSTRAINT_NAME = tc.CONSTRAINT_NAME"
                        + " WHERE tc.TABLE_NAME = 'CLAIM' AND tc.CONSTRAINT_TYPE = 'CHECK'"
                        + " AND UPPER(cc.CHECK_CLAUSE) LIKE '%AMOUNT%'")) {
            while (rs.next()) {
                checks.add(rs.getString(1));
            }
        }
        try (Statement statement = connection.createStatement()) {
            for (String check : checks) {
                statement.execute("ALTER TABLE claim DROP CONSTRAINT \"" + check + "\"");
            }
            statement.execute("ALTER TABLE claim ADD CONSTRAINT claim_correction_names_claim"
                    + " CHECK (amount >= 0 OR corrects IS NOT NULL)");
        }
    }
}
