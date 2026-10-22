package net.officefloor.hq.app.template;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.util.List;
import net.officefloor.hq.app.Audit;
import net.officefloor.hq.app.claim.Claim;
import net.officefloor.hq.app.claim.ClaimService;
import net.officefloor.hq.app.sheet.SheetService;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.server.ResponseStatusException;

/** The claim templates on a sheet: listed, saved (a name saved again replaces it), and claims added from them. */
@Service
public class ClaimTemplateService {

    private final ClaimTemplateRepository templates;
    private final SheetService sheets;
    private final ClaimService claims;
    private final Audit audit;

    public ClaimTemplateService(ClaimTemplateRepository templates, SheetService sheets, ClaimService claims, Audit audit) {
        this.templates = templates;
        this.sheets = sheets;
        this.claims = claims;
        this.audit = audit;
    }

    /** The sheet's templates by name. */
    @Transactional(readOnly = true)
    public List<ClaimTemplate> list(String sheetId) {
        return templates.findBySheetIdOrderByNameAsc(sheetId);
    }

    /**
     * Save a template under its (trimmed) name, replacing the one of that name; refused when the name is
     * blank or the amount is one the claim form refuses on a claim that corrects nothing (below 0.00).
     */
    @Transactional
    public ClaimTemplate save(String sheetId, String user, String name, BigDecimal amount, String note) {
        String trimmed = name == null ? "" : name.trim();
        if (trimmed.isEmpty()) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "A template needs a name.");
        }
        if (amount.signum() < 0) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "A template's amount cannot be below 0.00.");
        }
        BigDecimal money = amount.setScale(2, RoundingMode.UNNECESSARY);
        String text = note == null ? "" : note;
        // Take the sheet's lock first, so the same name saved twice at once replaces rather than duplicates.
        sheets.lock(sheetId);
        ClaimTemplate template = templates.findBySheetIdAndName(sheetId, trimmed)
                .map(t -> {
                    t.change(money, text);
                    return t;
                })
                .orElseGet(() -> new ClaimTemplate(sheetId, trimmed, money, text));
        ClaimTemplate saved = templates.saveAndFlush(template);
        audit.recordOnCommit("TEMPLATE_SAVED sheet=" + sheetId + " by=" + user + " name=" + trimmed
                + " amount=" + money.toPlainString() + " note=" + text);
        return saved;
    }

    /**
     * Add a DRAFT claim for a person with the template's amount and note as they are now, with no date,
     * by every rule of the claim form; refused when the sheet has no template of that name.
     */
    @Transactional
    public Claim claimFrom(String sheetId, String user, long personId, String name) {
        String trimmed = name == null ? "" : name.trim();
        ClaimTemplate template = templates.findBySheetIdAndName(sheetId, trimmed)
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND, "No template of that name."));
        return claims.add(sheetId, user, personId, template.getAmount(), null, template.getNote(), null);
    }
}
