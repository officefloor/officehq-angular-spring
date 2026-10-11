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
import java.util.List;
import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/clients")
public class ClientController {

    public record ClientView(Long id, String name, String email, Integer termsDays, BigDecimal discountPct,
            boolean taxExempt, Currency currency, BigDecimal earlyPayPct, Integer earlyPayDays,
            BigDecimal retentionPct) {
        static ClientView of(Client c) {
            return new ClientView(c.getId(), c.getName(), c.getEmail(), c.getTermsDays(), c.getDiscountPct(),
                    c.isTaxExempt(), c.getCurrency(), c.getEarlyPayPct(), c.getEarlyPayDays(),
                    c.getRetentionPct());
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
            @DecimalMin(value = "0", message = "The standard retention must be from 0 to 20 percent.")
            @DecimalMax(value = "20", message = "The standard retention must be from 0 to 20 percent.")
            @Digits(integer = 2, fraction = 2, message = "The standard retention can have at most two decimals.")
            BigDecimal retentionPct) {

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

    public ClientController(ClientService clients) {
        this.clients = clients;
    }

    @GetMapping
    public List<ClientView> list() {
        return clients.list().stream().map(ClientView::of).toList();
    }

    @PostMapping
    @ResponseStatus(HttpStatus.CREATED)
    public ClientView add(@Valid @RequestBody NewClient body) {
        return ClientView.of(clients.add(body.name(), body.email(), body.termsDays(), body.discountPct(),
                Boolean.TRUE.equals(body.taxExempt()), Currency.orUsd(body.currency()), body.earlyPayPct(), body.earlyPayDays(),
                body.retentionPct()));
    }
}
