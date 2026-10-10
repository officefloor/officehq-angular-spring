package net.officefloor.hq.app.dashboard;

import java.time.LocalDate;
import org.springframework.format.annotation.DateTimeFormat;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/dashboard")
public class DashboardController {

    private final DashboardService service;

    public DashboardController(DashboardService service) {
        this.service = service;
    }

    @GetMapping
    public DashboardResponse summary() {
        return service.summary();
    }

    /** The tax charged on invoices issued on or between the given dates. */
    @GetMapping("/tax-summary")
    public TaxSummaryResponse taxSummary(@RequestParam @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate from,
            @RequestParam @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate to) {
        return service.taxSummary(from, to);
    }

    /** The sales tax charged on invoices issued on or between the given dates, broken down by tax rate. */
    @GetMapping("/tax-report")
    public TaxReportResponse taxReport(@RequestParam @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate from,
            @RequestParam @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate to) {
        return service.taxReport(from, to);
    }

    /**
     * The revenue billed on invoices issued on or between the given dates, broken down by job. Without both dates the
     * revenue is over all time.
     */
    @GetMapping("/revenue-report")
    public RevenueReportResponse revenueReport(
            @RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate from,
            @RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate to) {
        return service.revenueReport(from, to);
    }

    /** How old the debt across all clients is, in the home currency, split into age buckets. */
    @GetMapping("/aging-report")
    public AgingReportResponse agingReport() {
        return service.agingReport();
    }

    /** The money expected in from the instalments still to be paid on sent invoices. */
    @GetMapping("/forecast")
    public ForecastResponse forecast() {
        return service.forecast();
    }
}
