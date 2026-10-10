package net.officefloor.hq.app.invoice;

import java.time.LocalDate;
import org.springframework.format.annotation.DateTimeFormat;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

/** A client's statement: every invoice raised across their projects and the total they still owe. */
@RestController
@RequestMapping("/api/clients/{clientId}/statement")
public class ClientStatementController {

    private final InvoiceService service;

    public ClientStatementController(InvoiceService service) {
        this.service = service;
    }

    @GetMapping
    public ClientStatementResponse get(@PathVariable Long clientId) {
        return service.statementForClient(clientId);
    }

    /** The client's billed, paid, outstanding and overdue totals on one screen. */
    @GetMapping("/summary")
    public ClientFinancialSummaryResponse summary(@PathVariable Long clientId) {
        return service.financialSummaryForClient(clientId);
    }

    /** What the client owed as at the end of the given day. */
    @GetMapping("/balance")
    public ClientBalanceAsOfResponse balanceAsOf(@PathVariable Long clientId,
            @RequestParam @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate asOf) {
        return service.balanceAsOf(clientId, asOf);
    }

    /** The client's statement for a date range: the opening balance, the entries within it and the closing balance. */
    @GetMapping("/range")
    public ClientStatementRangeResponse range(@PathVariable Long clientId,
            @RequestParam @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate from,
            @RequestParam @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate to) {
        return service.statementForRange(clientId, from, to);
    }

    /** How old the client's debt is: what is current, 31 to 60 days overdue, and more than 60 days overdue. */
    @GetMapping("/aging")
    public ClientAgingResponse aging(@PathVariable Long clientId) {
        return service.agingForClient(clientId);
    }
}
