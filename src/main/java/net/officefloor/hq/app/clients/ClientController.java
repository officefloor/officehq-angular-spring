package net.officefloor.hq.app.clients;

import jakarta.validation.Valid;
import jakarta.validation.constraints.AssertTrue;
import jakarta.validation.constraints.DecimalMax;
import jakarta.validation.constraints.DecimalMin;
import jakarta.validation.constraints.Digits;
import jakarta.validation.constraints.Email;
import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Size;
import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.List;
import net.officefloor.hq.app.invoices.InvoiceService;
import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.server.ResponseStatusException;

@RestController
@RequestMapping("/api/clients")
public class ClientController {

    public record ClientView(Long id, String name, String email, Integer termsDays, BigDecimal discountPct,
            boolean taxExempt, boolean pricesIncludeTax, Currency currency, BigDecimal earlyPayPct, Integer earlyPayDays,
            Integer graceDays, BigDecimal interestRate, String region, String taxNumber,
            List<RateView> rates, BigDecimal withholdingPct, BigDecimal minimumCharge, BigDecimal rateDiscountPct,
            LocalDate taxExemptUntil, BigDecimal creditLimit, boolean onHold, boolean overLimit, String riskScore, InvoiceService.RiskBand riskBand, ClientTier tier, BigDecimal upliftPct) {
        /**
         * The client, on hold when held by hand or while over its credit limit (overLimit), with its payment risk
         * score (null when nothing is paid in full) and band.
         */
        static ClientView of(Client c, boolean overLimit, InvoiceService.Risk risk) {
            return new ClientView(c.getId(), c.getName(), c.getEmail(), c.getTermsDays(), c.getDiscountPct(),
                    c.isTaxExempt(), c.isPricesIncludeTax(), c.getCurrency(), c.getEarlyPayPct(), c.getEarlyPayDays(),
                    c.getGraceDays(), c.getInterestRate(), c.getRegion(), c.getTaxNumber(),
                    c.getRates().stream().map(RateView::of).toList(), c.getWithholdingPct(), c.getMinimumCharge(),
                    c.getRateDiscountPct(), c.getTaxExemptUntil(), c.getCreditLimit(), c.isOnHold() || overLimit, overLimit,
                    risk.score() == null ? null : risk.score().toPlainString(), risk.band(), c.getTier(), c.getUpliftPct());
        }
    }

    /**
     * A rate card entry: its unit type, the date it takes effect from (null for always), and its single unit price,
     * or else its tiers (null when it has a single price).
     */
    public record RateView(String unitType, LocalDate from, BigDecimal unitPrice, List<RateTiers.Band> tiers, BigDecimal increment, BigDecimal minUnits,
            BigDecimal taxRate) {
        static RateView of(ClientRate r) {
            RateTiers tiers = r.tiers();
            return new RateView(r.getUnitType(), r.getEffectiveFrom(), r.getUnitPrice(), tiers == null ? null : tiers.bands(), r.getIncrement(), r.getMinUnits(), r.getTaxRate());
        }
    }

    public record NewClient(
            @NotBlank(message = "Enter the client's name.")
            @Size(max = 255, message = "The name must be at most 255 characters.")
            String name,
            @NotBlank(message = "Enter the client's email address.")
            @Email(regexp = PROPER_EMAIL,
                    message = "Enter a proper email address, like name@company.com.")
            @Size(max = 255, message = "The email must be at most 255 characters.")
            String email,
            @Min(value = 1, message = "Payment terms must be from 1 to 365 days.")
            @Max(value = 365, message = "Payment terms must be from 1 to 365 days.")
            Integer termsDays,
            @DecimalMin(value = "0", message = "The standard discount must be from 0 to 100 percent.")
            @DecimalMax(value = "100", message = "The standard discount must be from 0 to 100 percent.")
            @Digits(integer = 3, fraction = 2, message = "The standard discount can have at most two decimals.")
            BigDecimal discountPct,
            /** Whether the client is tax exempt; taxed when left out. */
            Boolean taxExempt,
            /** Whether the client is quoted prices that include tax; prices exclude it when left out. */
            Boolean pricesIncludeTax,
            /** The currency the client is billed in; US dollars when left out. */
            @Pattern(regexp = "USD|EUR|GBP|JPY|CHF", message = "The currency must be USD, EUR, GBP, JPY or CHF.")
            String currency,
            @DecimalMin(value = "0", message = "The early-payment discount must be from 0 to 100 percent.")
            @DecimalMax(value = "100", message = "The early-payment discount must be from 0 to 100 percent.")
            @Digits(integer = 3, fraction = 2, message = "The early-payment discount can have at most two decimals.")
            BigDecimal earlyPayPct,
            @Min(value = 1, message = "The early-payment window must be from 1 to 365 days.")
            @Max(value = 365, message = "The early-payment window must be from 1 to 365 days.")
            Integer earlyPayDays,
            /** The client's grace period in days; the standard week when left out. */
            @Min(value = 0, message = "The grace period must be from 0 to 30 days.")
            @Max(value = Client.MAX_GRACE_DAYS, message = "The grace period must be from 0 to 30 days.")
            Integer graceDays,
            /** The client's own late-interest rate; the workspace's when left out. */
            @DecimalMin(value = "0", message = "The interest rate must be from 0 to 30 percent.")
            @DecimalMax(value = "30", message = "The interest rate must be from 0 to 30 percent.")
            @Digits(integer = 2, fraction = 2, message = "The interest rate can have at most two decimals.")
            BigDecimal interestRate,
            /** The region the client is in; none when left out or blank. */
            @Size(max = 64, message = "The region must be at most 64 characters.")
            String region,
            /** The client's tax number; none when left out or blank. */
            String taxNumber,
            /** The percentage of the amount before tax the client withholds for its tax office; none when left out. */
            @DecimalMin(value = "0", message = "The withholding must be from 0 to 100 percent.")
            @DecimalMax(value = "100", message = "The withholding must be from 0 to 100 percent.")
            @Digits(integer = 3, fraction = 2, message = "The withholding can have at most two decimals.")
            BigDecimal withholdingPct,
            /** The client's own minimum charge in its currency; the workspace's when left out, none when 0. */
            @DecimalMin(value = "0", message = "The minimum charge cannot be negative.")
            @Digits(integer = 17, fraction = 2, message = "The minimum charge can have at most two decimals.")
            BigDecimal minimumCharge,
            /** The percentage taken off the prices the client's lines take from the rates; none when left out. */
            @DecimalMin(value = "0", message = "The rate discount must be from 0 to 100 percent.")
            @DecimalMax(value = "100", message = "The rate discount must be from 0 to 100 percent.")
            @Digits(integer = 3, fraction = 2, message = "The rate discount can have at most two decimals.")
            BigDecimal rateDiscountPct,
            /** The last issue date the tax exemption covers; it does not end when left out. Only for an exempt client. */
            LocalDate taxExemptUntil,
            /** The most the client may owe in its currency; no limit when left out. */
            @DecimalMin(value = "0", message = "The credit limit cannot be negative.")
            @Digits(integer = 17, fraction = 2, message = "The credit limit can have at most two decimals.")
            BigDecimal creditLimit,
            /** The client's tier; STANDARD when left out. */
            @Pattern(regexp = "STANDARD|KEY|NEW", message = "The tier must be STANDARD, KEY or NEW.")
            String tier,
            /** The yearly percentage rise of the client's typed recurring prices; none when left out. */
            @DecimalMin(value = "0", message = "The yearly price rise must be from 0 to 100 percent.")
            @DecimalMax(value = "100", message = "The yearly price rise must be from 0 to 100 percent.")
            @Digits(integer = 3, fraction = 2, message = "The yearly price rise can have at most two decimals.")
            BigDecimal upliftPct) {

        @AssertTrue(message = "Only a tax-exempt client can have an exemption end date: tick tax exempt or clear the date.")
        public boolean isTaxExemptUntilExempt() {
            return taxExemptUntil == null || Boolean.TRUE.equals(taxExempt);
        }

        @AssertTrue(message = "Enter a tax number of two letters then ten digits whose last two digits check the first eight, or leave it blank.")
        public boolean isTaxNumberValid() {
            return TaxNumber.normalize(taxNumber) == null || TaxNumber.isValid(taxNumber);
        }

        @AssertTrue(message = "Enter both the early-payment discount and its number of days, or neither.")
        public boolean isEarlyPayComplete() {
            return (earlyPayPct == null || earlyPayPct.signum() == 0) == (earlyPayDays == null);
        }
    }

    /**
     * A proper email address: a mailbox, an @, and a domain with at least one dot whose parts are
     * not empty — so name@company.com, but not name@company (a typo) or name.company.com.
     */
    static final String PROPER_EMAIL = "\\s*[^@\\s]+@[^@\\s.]+(\\.[^@\\s.]+)+\\s*";

    private final ClientService clients;

    private final InvoiceService invoices;

    public ClientController(ClientService clients, InvoiceService invoices) {
        this.clients = clients;
        this.invoices = invoices;
    }

    private ClientView view(Client c) {
        return ClientView.of(c, invoices.overLimit(c.getId()), invoices.riskOf(c.getId()));
    }

    @GetMapping
    public List<ClientView> list() {
        return clients.list().stream().map(this::view).toList();
    }

    @PostMapping
    @ResponseStatus(HttpStatus.CREATED)
    public ClientView add(@Valid @RequestBody NewClient body) {
        return view(clients.add(body.name(), body.email(), body.termsDays(), body.discountPct(),
                Boolean.TRUE.equals(body.taxExempt()), Boolean.TRUE.equals(body.pricesIncludeTax()), Currency.orUsd(body.currency()), body.earlyPayPct(), body.earlyPayDays(),
                body.graceDays(), body.interestRate(), body.region(), body.taxNumber(), body.withholdingPct(), body.minimumCharge(),
                body.rateDiscountPct(), body.taxExemptUntil(), body.creditLimit(), ClientTier.orStandard(body.tier()), body.upliftPct()));
    }

    /**
     * Puts the client on hold, or lifts the hold when it is on hold; answers the client. A client over its
     * credit limit is on hold until its balance is back within it, so its hold cannot be lifted.
     */
    @PostMapping("/{id}/hold")
    public ClientView toggleHold(@PathVariable Long id) {
        if (invoices.overLimit(id)) {
            throw new ClientRejectedException(
                    "The client owes more than its credit limit, so it stays on hold until its balance is back within it.");
        }
        return clients.toggleHold(id).map(this::view)
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND, "No such client."));
    }
}
