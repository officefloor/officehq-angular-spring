package db.migration;

import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.Statement;
import java.util.ArrayList;
import java.util.List;
import org.flywaydb.core.api.migration.BaseJavaMigration;
import org.flywaydb.core.api.migration.Context;

/**
 * A claim may be PENDING: settled by a clerk above the clerk limit, awaiting a steward's approval.
 * Replaces the unnamed check on claim.status from V7, looking up the name the database gave it.
 */
public class V28__claim_pending extends BaseJavaMigration {

    @Override
    public void migrate(Context context) throws Exception {
        List<String> names = new ArrayList<>();
        try (PreparedStatement query = context.getConnection().prepareStatement(
                "SELECT cc.CONSTRAINT_NAME FROM INFORMATION_SCHEMA.CHECK_CONSTRAINTS cc"
                        + " JOIN INFORMATION_SCHEMA.TABLE_CONSTRAINTS tc"
                        + " ON tc.CONSTRAINT_SCHEMA = cc.CONSTRAINT_SCHEMA AND tc.CONSTRAINT_NAME = cc.CONSTRAINT_NAME"
                        + " WHERE tc.TABLE_NAME = 'CLAIM' AND UPPER(cc.CHECK_CLAUSE) LIKE '%STATUS%'");
                ResultSet rows = query.executeQuery()) {
            while (rows.next()) names.add(rows.getString(1));
        }
        try (Statement alter = context.getConnection().createStatement()) {
            for (String name : names) alter.execute("ALTER TABLE claim DROP CONSTRAINT \"" + name + "\"");
            alter.execute("ALTER TABLE claim ADD CONSTRAINT claim_status_check"
                    + " CHECK (status IN ('DRAFT', 'OPEN', 'PENDING', 'SETTLED', 'VOID'))");
        }
    }
}
