package net.officefloor.hq.app.claim;

import java.util.List;
import net.officefloor.hq.app.sheet.SheetRepository;
import net.officefloor.hq.app.sheet.SheetService;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.server.ResponseStatusException;

/**
 * The void reasons on a sheet, in the order they were added; no reason is on it twice. While the list is not
 * empty, a claim is voided only with a reason on it (see {@link #require}).
 */
@Service
public class VoidReasons {

    /** The longest a reason may be. */
    static final int REASON_MAX = 100;

    private final VoidReasonRepository reasons;
    private final SheetService sheets;
    private final SheetRepository sheetRows;
    private final ApplicationEventPublisher events;

    public VoidReasons(VoidReasonRepository reasons, SheetService sheets, SheetRepository sheetRows,
            ApplicationEventPublisher events) {
        this.reasons = reasons;
        this.sheets = sheets;
        this.sheetRows = sheetRows;
        this.events = events;
    }

    /** The reasons on the sheet, in the order they were added. */
    @Transactional(readOnly = true)
    public List<String> list(String sheetId) {
        return reasons.findBySheetIdOrderByIdAsc(sheetId).stream().map(VoidReason::getReason).toList();
    }

    /** Add a reason, trimmed; refused when it is blank, too long or already on the sheet's list. */
    @Transactional
    public String add(String sheetId, String user, String text) {
        String reason = text == null ? "" : text.strip();
        if (reason.isEmpty()) {
            throw new VoidReasonRefused("Enter a reason.");
        }
        if (reason.length() > REASON_MAX) {
            throw new VoidReasonRefused("The reason is too long.");
        }
        sheets.load(sheetId, user);
        sheetRows.lockById(sheetId);
        if (reasons.existsBySheetIdAndReason(sheetId, reason)) {
            throw new VoidReasonRefused("That reason is already on this sheet's list.");
        }
        reasons.saveAndFlush(new VoidReason(sheetId, reason));
        events.publishEvent(new VoidReasonAdded(sheetId, user, reason));
        return reason;
    }

    /**
     * The reason a claim on the sheet is voided with: null while the sheet's list is empty (any reason given is
     * then ignored); otherwise the reason given, trimmed, which must be on the list, else the void is refused.
     */
    @Transactional(readOnly = true)
    public String require(String sheetId, String given) {
        if (!reasons.existsBySheetId(sheetId)) {
            return null;
        }
        String reason = given == null ? "" : given.strip();
        if (reason.isEmpty()) {
            throw new ResponseStatusException(HttpStatus.CONFLICT, "Choose a reason to void the claim.");
        }
        if (!reasons.existsBySheetIdAndReason(sheetId, reason)) {
            throw new ResponseStatusException(HttpStatus.CONFLICT, "That reason is not on this sheet's list.");
        }
        return reason;
    }
}
