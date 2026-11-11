package net.officefloor.hq.app.claim;

import java.math.BigDecimal;
import java.util.List;
import net.officefloor.hq.app.Audit;
import net.officefloor.hq.app.sheet.SheetNotFoundException;
import net.officefloor.hq.app.sheet.SheetRepository;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * Keeps the claim templates of a sheet, one per name: saving a name again replaces its amount and note. A claim added
 * from a template is a DRAFT with the template's amount and note at that moment, dated and checked as the claim form's
 * claim with no date; it keeps no tie to the template.
 */
@Service
public class ClaimTemplateService {

    private final ClaimTemplateRepository templates;
    private final SheetRepository sheets;
    private final ClaimService claims;
    private final Audit audit;

    public ClaimTemplateService(ClaimTemplateRepository templates, SheetRepository sheets, ClaimService claims,
            Audit audit) {
        this.templates = templates;
        this.sheets = sheets;
        this.claims = claims;
        this.audit = audit;
    }

    public List<ClaimTemplate> list(String sheetId) {
        return templates.findBySheetIdOrderByNameAsc(sheetId);
    }

    /** Save the template (name trimmed, not blank; amount as the claim form takes it, not negative; note trimmed). */
    @Transactional
    public ClaimTemplate save(String sheetId, String user, String name, BigDecimal amount, String note) {
        if (!sheets.existsById(sheetId)) {
            throw new SheetNotFoundException(sheetId);
        }
        ClaimTemplate template = templates.findBySheetIdAndName(sheetId, name)
                .orElseGet(() -> new ClaimTemplate(sheetId, name, amount, note));
        template.setAmount(amount);
        template.setNote(note);
        ClaimTemplate saved = templates.saveAndFlush(template);
        audit.recordOnCommit("TEMPLATE_SAVED sheet=" + sheetId + " by=" + user + " name=" + saved.getName()
                + " amount=" + saved.getAmount().toPlainString() + " note=" + saved.getNote());
        return saved;
    }

    /** Add a DRAFT claim for the person from the named template, as the claim form adds one with no date. */
    @Transactional
    public Claim addClaim(String sheetId, String user, Long personId, String name) {
        ClaimTemplate template = templates.findBySheetIdAndName(sheetId, name)
                .orElseThrow(() -> new ClaimTemplateNotFoundException(sheetId, name));
        return claims.add(sheetId, user, personId, template.getAmount(), null, template.getNote(), null, null, null);
    }
}
