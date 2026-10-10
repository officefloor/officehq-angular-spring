package net.officefloor.hq.app.taxadjustment;

import java.math.BigDecimal;
import java.util.List;
import net.officefloor.hq.app.Audit;
import net.officefloor.hq.app.settings.SettingsService;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.server.ResponseStatusException;

/** Keeps the manual adjustments to the tax owed. */
@Service
public class TaxAdjustmentService {

    private final TaxAdjustmentRepository adjustments;
    private final SettingsService settings;
    private final Audit audit;

    public TaxAdjustmentService(TaxAdjustmentRepository adjustments, SettingsService settings, Audit audit) {
        this.adjustments = adjustments;
        this.settings = settings;
        this.audit = audit;
    }

    /** The recorded adjustments, earliest date first. */
    @Transactional(readOnly = true)
    public List<TaxAdjustmentResponse> list() {
        return adjustments.findAllByOrderByAdjustmentDateAscIdAsc().stream().map(TaxAdjustmentResponse::from).toList();
    }

    /** Records an adjustment in the home currency, recording it in the audit log. A zero adjustment is refused. */
    @Transactional
    public TaxAdjustmentResponse record(TaxAdjustmentRequest request) {
        if (request.amount().signum() == 0) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "A tax adjustment must not be zero");
        }
        String reason = request.reason() == null || request.reason().isBlank() ? null : request.reason().trim();
        BigDecimal amount = request.amount().setScale(2);
        TaxAdjustment saved = adjustments.saveAndFlush(
                new TaxAdjustment(request.date(), amount, settings.homeCurrency(), reason));
        audit.record("TAX_ADJUSTMENT_RECORDED id=" + saved.getId() + " date=" + saved.getAdjustmentDate()
                + " amount=" + saved.getAmount().toPlainString() + " currency=" + saved.getCurrency());
        return TaxAdjustmentResponse.from(saved);
    }
}
