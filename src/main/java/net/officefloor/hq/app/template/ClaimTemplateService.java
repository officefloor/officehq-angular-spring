package net.officefloor.hq.app.template;

import java.math.BigDecimal;
import java.util.List;
import net.officefloor.hq.app.Audit;
import net.officefloor.hq.app.claim.Claim;
import net.officefloor.hq.app.claim.ClaimService;
import net.officefloor.hq.app.sheet.SheetService;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.server.ResponseStatusException;

@Service
public class ClaimTemplateService {

    private final ClaimTemplateRepository templates;
    private final ClaimService claims;
    private final SheetService sheets;
    private final Audit audit;

    public ClaimTemplateService(ClaimTemplateRepository templates, ClaimService claims, SheetService sheets, Audit audit) {
        this.templates = templates;
        this.claims = claims;
        this.sheets = sheets;
        this.audit = audit;
    }

    /** The sheet's templates, by name. */
    @Transactional(readOnly = true)
    public List<ClaimTemplate> list(String sheetId) {
        return templates.findBySheetIdOrderByNameAsc(sheetId);
    }

    /** Save a template on the sheet, the sheet created first if it was never loaded; a name saved
     *  already has its amount and note replaced. */
    @Transactional
    public ClaimTemplate save(String sheetId, String user, String name, BigDecimal amount, String note) {
        sheets.load(sheetId, user);
        sheets.lock(sheetId);
        String trimmedNote = note == null ? "" : note.trim();
        ClaimTemplate template = templates.findBySheetIdAndName(sheetId, name).orElse(null);
        if (template == null) {
            template = templates.saveAndFlush(new ClaimTemplate(sheetId, name, amount, trimmedNote));
        } else {
            template.replace(amount, trimmedNote);
            templates.flush();
        }
        audit.recordOnCommit("TEMPLATE_SAVED sheet=" + sheetId + " by=" + user + " name=" + name
                + " amount=" + amount.setScale(2).toPlainString() + " note=" + trimmedNote);
        return template;
    }

    /** Add a DRAFT claim for the person with the template's amount and note as they are now, dated
     *  as a claim added with no date, by every rule of the claim form; refused (404) when the
     *  sheet has no template of the name. */
    @Transactional
    public Claim addClaim(String sheetId, String user, long personId, String name) {
        ClaimTemplate template = templates.findBySheetIdAndName(sheetId, name)
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND, "No such template"));
        return claims.add(sheetId, user, personId, template.getAmount(), null, template.getNote(), null, null, null);
    }
}
