package net.officefloor.hq.app.role;

import java.util.Optional;
import net.officefloor.hq.app.sheet.Sheet;
import net.officefloor.hq.app.sheet.SheetRepository;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

/** Which steward, if any, a user acts for on a sheet's today. Kept apart from {@link DelegationService} so the audit
 *  sink and the role rules can ask it without depending on what records audit lines. */
@Component
public class Delegations {

    private final DelegationRepository delegations;
    private final SheetRepository sheets;

    public Delegations(DelegationRepository delegations, SheetRepository sheets) {
        this.delegations = delegations;
        this.sheets = sheets;
    }

    /** The steward who delegated the steward role to the user for a range holding the sheet's today (the latest such
     *  delegation), or empty when the user acts for no one. */
    @Transactional(readOnly = true)
    public Optional<String> actingFor(String sheetId, String user) {
        return sheets.findById(sheetId).map(Sheet::getToday).flatMap(today ->
                delegations.findBySheetIdAndUserNameOrderByIdDesc(sheetId, user).stream()
                        .filter(d -> d.covers(today))
                        .map(Delegation::getDelegatedBy)
                        .findFirst());
    }
}
