package net.officefloor.hq.app.asof;

import java.sql.Connection;
import java.sql.SQLException;
import javax.sql.DataSource;
import org.springframework.jdbc.datasource.DelegatingDataSource;

/** The app's data source: the store itself, or for a request answered as of a date the sheet as it then stood. */
public class AsOfDataSource extends DelegatingDataSource {

    public AsOfDataSource(DataSource target) {
        super(target);
    }

    @Override
    public Connection getConnection() throws SQLException {
        DataSource store = AsOfContext.store();
        return store != null ? store.getConnection() : super.getConnection();
    }

    @Override
    public Connection getConnection(String username, String password) throws SQLException {
        DataSource store = AsOfContext.store();
        return store != null ? store.getConnection() : super.getConnection(username, password);
    }
}
