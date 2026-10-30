package net.officefloor.hq.app.session;

import java.util.List;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/** Reads and saves the columns of the people list each user has switched off. Choosing writes no audit line. */
@Service
public class HiddenColumnService {

    private final HiddenColumnRepository columns;

    public HiddenColumnService(HiddenColumnRepository columns) {
        this.columns = columns;
    }

    @Transactional(readOnly = true)
    public List<String> hiddenOf(String user) {
        return columns.findByUserOrderByColumnAsc(user).stream().map(HiddenColumn::getColumn).toList();
    }

    /** Show or hide the one column; answers with every column the user has switched off now. */
    @Transactional
    public List<String> setShown(String user, String column, boolean shown) {
        if (shown) {
            columns.show(user, column);
        } else {
            columns.hide(user, column);
        }
        return hiddenOf(user);
    }
}
