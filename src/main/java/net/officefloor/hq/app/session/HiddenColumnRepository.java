package net.officefloor.hq.app.session;

import java.util.List;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

public interface HiddenColumnRepository extends JpaRepository<HiddenColumn, HiddenColumn.Key> {

    List<HiddenColumn> findByUserOrderByColumnAsc(String user);

    @Modifying
    @Query(value = "MERGE INTO user_hidden_column (user_name, column_key) KEY (user_name, column_key) VALUES (:user, :column)",
            nativeQuery = true)
    void hide(@Param("user") String user, @Param("column") String column);

    @Modifying
    @Query("DELETE FROM HiddenColumn h WHERE h.user = :user AND h.column = :column")
    void show(@Param("user") String user, @Param("column") String column);
}
