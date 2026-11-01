package net.officefloor.hq.app.template;

import java.math.BigDecimal;
import java.util.List;
import net.officefloor.hq.app.Audit;
import net.officefloor.hq.app.claim.Claim;
import net.officefloor.hq.app.claim.ClaimService;
import net.officefloor.hq.app.sheet.SheetNotFoundException;
import net.officefloor.hq.app.sheet.SheetRepository;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * The sheet's claim templates, each name once. A claim made from one is a DRAFT taking the template's
 * amount and note at that moment, added by every rule of the claim form; changing the template later
 * leaves it as it is.
 */
@Service
public class ClaimTemplateService {

    private final SheetRepository sheets;
    private final ClaimTemplateRepository templates;
    private final ClaimService claims;
    private final Audit audit;

    public ClaimTemplateService(SheetRepository sheets, ClaimTemplateRepository templates, ClaimService claims, Audit audit) {
        this.sheets = sheets;
        this.templates = templates;
        this.claims = claims;
        this.audit = audit;
    }

    /** The sheet's templates, by name. */
    @Transactional(readOnly = true)
    public List<ClaimTemplate> templates(String sheetId) {
        return templates.findBySheetIdOrderByNameAsc(sheetId);
    }

    /** Save a template under a (trimmed) name, replacing the one already saved under it. */
    @Transactional
    public ClaimTemplate save(String sheetId, String user, String name, BigDecimal amount, String note) {
        sheets.findForUpdate(sheetId).orElseThrow(SheetNotFoundException::new);
        ClaimTemplate template = templates.findBySheetIdAndName(sheetId, name).orElse(null);
        if (template == null) template = new ClaimTemplate(sheetId, name, amount, note);
        else template.replace(amount, note);
        ClaimTemplate saved = templates.saveAndFlush(template);
        audit.recordOnCommit("TEMPLATE_SAVED sheet=" + sheetId + " by=" + user.strip() + " name=" + name
                + " amount=" + amount.toPlainString() + " note=" + note);
        return saved;
    }

    /** Add a DRAFT claim for a person with the template's amount and note now, dated as a claim with no date. */
    @Transactional
    public Claim claimFrom(String sheetId, String user, Long personId, String name) {
        sheets.findForUpdate(sheetId).orElseThrow(SheetNotFoundException::new);
        ClaimTemplate template = templates.findBySheetIdAndName(sheetId, name).orElseThrow(ClaimTemplateNotFoundException::new);
        return claims.add(sheetId, user, personId, template.getAmount(), null, template.getNote(), null);
    }
}
