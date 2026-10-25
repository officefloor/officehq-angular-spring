package net.officefloor.hq.app.asof;

import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import java.io.IOException;
import java.io.UncheckedIOException;
import java.time.LocalDate;
import java.util.Optional;
import org.springframework.stereotype.Component;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;
import org.springframework.web.filter.OncePerRequestFilter;
import org.springframework.web.server.ResponseStatusException;

/**
 * Serves a read from a screen set to an as-of date from the sheet as it stood then: the read runs
 * in one transaction that first puts the sheet back to what {@link SheetHistory} kept for that date
 * (with the as-of date as its today) and is always rolled back, so the store and the audit file are
 * left as they were. Every other request on a sheet is marked as a change of it, so each of its
 * commits is kept in the sheet's history.
 */
@Component
public class AsOfFilter extends OncePerRequestFilter {

    private final SheetHistory history;
    private final TransactionTemplate transactions;

    public AsOfFilter(SheetHistory history, PlatformTransactionManager transactionManager) {
        this.history = history;
        this.transactions = new TransactionTemplate(transactionManager);
    }

    @Override
    protected void doFilterInternal(HttpServletRequest request, HttpServletResponse response, FilterChain chain)
            throws ServletException, IOException {
        Optional<String> sheet = AsOf.sheetOf(request);
        if (sheet.isEmpty()) {
            chain.doFilter(request, response);
            return;
        }
        Optional<LocalDate> asOf;
        try {
            asOf = AsOf.of(request);
        } catch (ResponseStatusException e) {
            response.sendError(e.getStatusCode().value(), e.getReason());
            return;
        }
        if (asOf.isPresent() && AsOf.reads(request)) {
            readAsOf(sheet.get(), asOf.get(), request, response, chain);
        } else if (asOf.isEmpty() && !AsOf.reads(request)) {
            history.changing(sheet.get(), () -> chain.doFilter(request, response));
        } else {
            chain.doFilter(request, response);
        }
    }

    private void readAsOf(String sheet, LocalDate asOf, HttpServletRequest request, HttpServletResponse response,
            FilterChain chain) throws ServletException, IOException {
        try {
            transactions.executeWithoutResult((status) -> {
                status.setRollbackOnly();
                history.restore(sheet, asOf);
                try {
                    chain.doFilter(request, response);
                } catch (IOException e) {
                    throw new UncheckedIOException(e);
                } catch (ServletException e) {
                    throw new WrappedServletException(e);
                }
            });
        } catch (UncheckedIOException e) {
            throw e.getCause();
        } catch (WrappedServletException e) {
            throw (ServletException) e.getCause();
        }
    }

    private static final class WrappedServletException extends RuntimeException {
        WrappedServletException(ServletException cause) {
            super(cause);
        }
    }
}
