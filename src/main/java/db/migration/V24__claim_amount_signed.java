package db.migration;

import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.Statement;
import java.util.ArrayList;
import java.util.List;
import org.flywaydb.core.api.migration.BaseJavaMigration;
import org.flywaydb.core.api.migration.Context;

/**
 * A claim's amount may be negative: a correction. Drops the unnamed check on claim.amount (amount >= 0)
 * from V5, looking up the name the database gave it.
 */
public class V24__claim_amount_signed extends BaseJavaMigration {

    @Override
    public void migrate(Context context) throws Exception {
        List<String> names = new ArrayList<>();
        try (PreparedStatement query = context.getConnection().prepareStatement(
                "SELECT cc.CONSTRAINT_NAME FROM INFORMATION_SCHEMA.CHECK_CONSTRAINTS cc"
                        + " JOIN INFORMATION_SCHEMA.TABLE_CONSTRAINTS tc"
                        + " ON tc.CONSTRAINT_SCHEMA = cc.CONSTRAINT_SCHEMA AND tc.CONSTRAINT_NAME = cc.CONSTRAINT_NAME"
                        + " WHERE tc.TABLE_NAME = 'CLAIM' AND UPPER(cc.CHECK_CLAUSE) LIKE '%AMOUNT%'");
                ResultSet rows = query.executeQuery()) {
            while (rows.next()) names.add(rows.getString(1));
        }
        try (Statement drop = context.getConnection().createStatement()) {
            for (String name : names) drop.execute("ALTER TABLE claim DROP CONSTRAINT \"" + name + "\"");
        }
    }
}
