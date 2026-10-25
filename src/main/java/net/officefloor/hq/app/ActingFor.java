package net.officefloor.hq.app;

import java.util.Optional;

/** Who a user acts for on a sheet: the steward who delegated the steward role to the user, while the
 *  delegation covers the sheet's today. */
public interface ActingFor {

    Optional<String> actingFor(String sheetId, String user);
}
