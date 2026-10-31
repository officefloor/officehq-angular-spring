package net.officefloor.hq.app.claimtemplate;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.util.List;
import net.officefloor.hq.app.Audit;
import net.officefloor.hq.app.claim.Claim;
import net.officefloor.hq.app.claim.ClaimService;
import net.officefloor.hq.app.sheet.SheetRepository;
import net.officefloor.hq.app.sheet.SheetService;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.server.ResponseStatusException;

/** The claim templates on a sheet, and the DRAFT claims a person's row adds from them. */
@Service
public class ClaimTemplateService {

    private final ClaimTemplateRepository templates;
    private final ClaimService claims;
    private final SheetService sheets;
    private final SheetRepository sheetStore;
    private final Audit audit;

    public ClaimTemplateService(ClaimTemplateRepository templates, ClaimService claims, SheetService sheets,
            SheetRepository sheetStore, Audit audit) {
        this.templates = templates;
        this.claims = claims;
        this.sheets = sheets;
        this.sheetStore = sheetStore;
        this.audit = audit;
    }

    /** The sheet's templates, by name. */
    @Transactional(readOnly = true)
    public List<ClaimTemplate> list(String sheetId) {
        return templates.findBySheetIdOrderByNameAsc(sheetId);
    }

    /** Save a template under its name, trimmed; saving a name again replaces its amount and note. Refused when the
     *  name is blank or the amount is one the claim form refuses for a claim that corrects none (negative, or with
     *  more than two decimals). */
    @Transactional
    public ClaimTemplate save(String sheetId, String user, String name, BigDecimal amount, String note) {
        String key = name == null ? "" : name.strip();
        if (key.isEmpty()) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "A template has a name");
        }
        if (amount == null || amount.signum() < 0 || amount.stripTrailingZeros().scale() > 2) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "A template's amount is not negative, with up to two decimals");
        }
        BigDecimal scaled = amount.setScale(2, RoundingMode.UNNECESSARY);
        String text = note == null ? "" : note.trim();
        sheets.load(sheetId, user);
        sheetStore.findLockedById(sheetId).orElseThrow();
        ClaimTemplate template = templates.findBySheetIdAndName(sheetId, key).orElse(null);
        if (template == null) {
            template = new ClaimTemplate(sheetId, key, scaled, text);
        } else {
            template.replace(scaled, text);
        }
        ClaimTemplate saved = templates.saveAndFlush(template);
        audit.recordChangeOnCommit(sheetId, user, "TEMPLATE_SAVED sheet=" + sheetId + " by=" + user
                + " name=" + saved.getName() + " amount=" + saved.getAmount().toPlainString() + " note=" + saved.getNote());
        return saved;
    }

    /** Add a DRAFT claim for the person with the named template's amount and note as they are now, with no date,
     *  by every rule of the claim form. Refused when the sheet has no template of that name. */
    @Transactional
    public Claim addClaim(String sheetId, String user, long personId, String name) {
        String key = name == null ? "" : name.strip();
        ClaimTemplate template = templates.findBySheetIdAndName(sheetId, key)
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND, "No such template on this sheet"));
        return claims.add(sheetId, user, personId, template.getAmount(), null, template.getNote(), null, null);
    }
}
