package net.officefloor.hq.app.asof;

import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import java.io.IOException;
import java.io.UncheckedIOException;
import java.time.LocalDate;
import java.util.Optional;
import java.util.concurrent.locks.Lock;
import java.util.concurrent.locks.ReentrantReadWriteLock;
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
 * commits is kept in the sheet's history. Putting a sheet back rewrites its rows until the rollback, so
 * a read as of a date runs on its own: never alongside a change, which would deadlock with it on those rows.
 * A read from a screen set to a preview date (see {@link Preview}) runs the same way, over the sheet's
 * current rows with only its today set to the preview date.
 */
@Component
public class AsOfFilter extends OncePerRequestFilter {

    private final SheetHistory history;
    private final TransactionTemplate transactions;
    /** Changes share it; a read as of a date holds it alone. */
    private final ReentrantReadWriteLock asOfReads = new ReentrantReadWriteLock(true);

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
        Optional<LocalDate> preview;
        try {
            asOf = AsOf.of(request);
            preview = Preview.of(request);
        } catch (ResponseStatusException e) {
            response.sendError(e.getStatusCode().value(), e.getReason());
            return;
        }
        if (asOf.isPresent() && AsOf.reads(request)) {
            Lock alone = asOfReads.writeLock();
            alone.lock();
            try {
                readAsOf(sheet.get(), asOf.get(), request, response, chain);
            } finally {
                alone.unlock();
            }
        } else if (asOf.isEmpty() && preview.isPresent() && Preview.previews(request)) {
            Lock alone = asOfReads.writeLock();
            alone.lock();
            try {
                readRolledBack(() -> history.preview(sheet.get(), preview.get()), request, response, chain);
            } finally {
                alone.unlock();
            }
        } else if (asOf.isEmpty() && !AsOf.reads(request)) {
            Lock shared = asOfReads.readLock();
            shared.lock();
            try {
                history.changing(sheet.get(), () -> chain.doFilter(request, response));
            } finally {
                shared.unlock();
            }
        } else {
            chain.doFilter(request, response);
        }
    }

    private void readAsOf(String sheet, LocalDate asOf, HttpServletRequest request, HttpServletResponse response,
            FilterChain chain) throws ServletException, IOException {
        readRolledBack(() -> history.restoreWithParents(sheet, asOf), request, response, chain);
    }

    /** Serve the read in one transaction that first sets the sheet up and is always rolled back. */
    private void readRolledBack(Runnable setUp, HttpServletRequest request, HttpServletResponse response,
            FilterChain chain) throws ServletException, IOException {
        try {
            transactions.executeWithoutResult((status) -> {
                status.setRollbackOnly();
                setUp.run();
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
