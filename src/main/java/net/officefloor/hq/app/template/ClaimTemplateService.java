package net.officefloor.hq.app.template;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.util.List;
import net.officefloor.hq.app.Audit;
import net.officefloor.hq.app.claim.Claim;
import net.officefloor.hq.app.claim.ClaimService;
import net.officefloor.hq.app.role.RoleService;
import net.officefloor.hq.app.sheet.SheetRepository;
import net.officefloor.hq.app.sheet.SheetService;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.server.ResponseStatusException;

/**
 * The sheet's claim templates: a name (trimmed) with an amount and a note, saved from a form;
 * saving a name again replaces its amount and note. A claim made from a template is added as the
 * claim form adds one with no date, copying the template's amount and note as they are then.
 */
@Service
public class ClaimTemplateService {

    private final ClaimTemplateRepository templates;
    private final ClaimService claims;
    private final SheetService sheets;
    private final SheetRepository sheetRows;
    private final Audit audit;
    private final RoleService roles;

    public ClaimTemplateService(ClaimTemplateRepository templates, ClaimService claims, SheetService sheets,
            SheetRepository sheetRows, Audit audit, RoleService roles) {
        this.templates = templates;
        this.claims = claims;
        this.sheets = sheets;
        this.sheetRows = sheetRows;
        this.audit = audit;
        this.roles = roles;
    }

    @Transactional(readOnly = true)
    public List<ClaimTemplate> list(String sheetId) {
        return templates.findBySheetIdOrderByNameAsc(sheetId);
    }

    /** Save a template (name and note trimmed); a blank name is refused (422). */
    @Transactional
    public ClaimTemplate save(String sheetId, String user, String name, BigDecimal amount, String note) {
        roles.refuseViewer(sheetId, user);
        sheets.load(sheetId, user);
        String named = name == null ? "" : name.trim();
        if (named.isEmpty()) {
            throw new ResponseStatusException(HttpStatus.UNPROCESSABLE_ENTITY, "Enter a template name.");
        }
        String noted = note == null ? "" : note.trim();
        // hold the sheet so the same name saved twice at the same moment is saved one after the other
        sheetRows.findLockedById(sheetId);
        ClaimTemplate template = templates.findBySheetIdAndName(sheetId, named).orElse(null);
        if (template == null) {
            template = new ClaimTemplate(sheetId, named, amount, noted);
        } else {
            template.setAmount(amount);
            template.setNote(noted);
        }
        ClaimTemplate saved = templates.saveAndFlush(template);
        audit.recordOnCommit("TEMPLATE_SAVED sheet=" + sheetId + " by=" + user + " name=" + saved.getName()
                + " amount=" + saved.getAmount().setScale(2, RoundingMode.UNNECESSARY).toPlainString()
                + " note=" + saved.getNote());
        return saved;
    }

    /** Add a claim to the person from the named template, as the claim form adds one with no date. */
    @Transactional
    public Claim addClaim(String sheetId, String user, long personId, String name) {
        ClaimTemplate template = templates.findBySheetIdAndName(sheetId, name == null ? "" : name.trim())
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND, "No such template on this sheet."));
        return claims.add(sheetId, user, personId, template.getAmount().setScale(2, RoundingMode.UNNECESSARY), null,
                template.getNote(), null, null, List.of());
    }
}
