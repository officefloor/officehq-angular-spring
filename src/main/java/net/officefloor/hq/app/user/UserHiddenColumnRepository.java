package net.officefloor.hq.app.user;

import java.util.List;
import org.springframework.data.jpa.repository.JpaRepository;

public interface UserHiddenColumnRepository extends JpaRepository<UserHiddenColumn, UserHiddenColumn.Key> {

    List<UserHiddenColumn> findByKeyUserName(String userName);
}
