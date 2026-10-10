package net.officefloor.hq.app.dashboard;

import java.nio.charset.StandardCharsets;
import java.time.LocalDate;
import org.springframework.http.ContentDisposition;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
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

    /** Downloads the revenue report (over the given dates, or all time without both) as a CSV file. */
    @GetMapping(value = "/revenue-report/export", produces = "text/csv")
    public ResponseEntity<String> exportRevenueReport(
            @RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate from,
            @RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate to) {
        return ResponseEntity.ok()
                .header(HttpHeaders.CONTENT_DISPOSITION,
                        ContentDisposition.attachment().filename("revenue-report.csv").build().toString())
                .contentType(new MediaType("text", "csv", StandardCharsets.UTF_8))
                .body(service.revenueReportCsv(from, to));
    }

    /** The revenue billed in two periods (each on or between its dates), side by side. */
    @GetMapping("/revenue-compare")
    public RevenueComparisonResponse revenueComparison(
            @RequestParam @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate aFrom,
            @RequestParam @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate aTo,
            @RequestParam @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate bFrom,
            @RequestParam @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate bTo) {
        return service.revenueComparison(aFrom, aTo, bFrom, bTo);
    }

    /** How old the debt across all clients is, in the home currency, split into age buckets. */
    @GetMapping("/aging-report")
    public AgingReportResponse agingReport() {
        return service.agingReport();
    }

    /** What was overdue at the close of each recent month, in the home currency, ending with the current month. */
    @GetMapping("/overdue-trend")
    public OverdueTrendResponse overdueTrend() {
        return service.overdueTrend();
    }

    /** The money expected in from the instalments still to be paid on sent invoices. */
    @GetMapping("/forecast")
    public ForecastResponse forecast() {
        return service.forecast();
    }
}
