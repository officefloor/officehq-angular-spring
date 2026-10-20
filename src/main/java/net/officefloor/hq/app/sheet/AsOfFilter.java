package net.officefloor.hq.app.sheet;

import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.time.LocalDate;
import java.time.format.DateTimeParseException;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import org.springframework.http.HttpMethod;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.stereotype.Component;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;
import org.springframework.web.filter.OncePerRequestFilter;
import org.springframework.web.util.UriUtils;

/**
 * A sheet shown as of a date (the {@code X-As-Of} header, set by the screen of the user viewing it): every read of the
 * sheet answers from the sheet put back as it stood at the end of that date, with that date as its today, in a
 * transaction that is then rolled back, so nothing is changed and nothing is recorded; every change asked of it is
 * refused. Loading a sheet goes through. Each change made to a sheet otherwise is kept in its history (see
 * {@link SheetHistory}).
 */
@Component
public class AsOfFilter extends OncePerRequestFilter {

    public static final String HEADER = "X-As-Of";

    private static final Pattern SHEET = Pattern.compile("^/api/sheets/([^/]+)(/.*)?$");

    private final SheetHistory history;
    private final TransactionTemplate transactions;

    public AsOfFilter(SheetHistory history, PlatformTransactionManager transactionManager) {
        this.history = history;
        this.transactions = new TransactionTemplate(transactionManager);
    }

    @Override
    protected void doFilterInternal(HttpServletRequest request, HttpServletResponse response, FilterChain chain)
            throws ServletException, IOException {
        Matcher m = SHEET.matcher(request.getRequestURI().substring(request.getContextPath().length()));
        String sheetId = m.matches() && !(m.group(1).equals("load") && m.group(2) == null)
                ? UriUtils.decode(m.group(1), StandardCharsets.UTF_8)
                : null;
        if (sheetId == null) {
            chain.doFilter(request, response);
            return;
        }
        boolean read = HttpMethod.GET.matches(request.getMethod()) || HttpMethod.HEAD.matches(request.getMethod())
                || HttpMethod.OPTIONS.matches(request.getMethod());
        String header = request.getHeader(HEADER);
        if (header != null && !header.isBlank()) {
            LocalDate asOf;
            try {
                asOf = LocalDate.parse(header.trim());
            } catch (DateTimeParseException e) {
                refuse(response, HttpStatus.BAD_REQUEST, "The as-of date is not a date.");
                return;
            }
            if (!read) {
                refuse(response, HttpStatus.UNPROCESSABLE_CONTENT,
                        "Nothing can be changed while the sheet is shown as of a date.");
                return;
            }
            asOf(sheetId, asOf, request, response, chain);
            return;
        }
        if (read) {
            chain.doFilter(request, response);
            return;
        }
        transactions.executeWithoutResult(s -> history.keepFirst(sheetId));
        try {
            chain.doFilter(request, response);
        } finally {
            transactions.executeWithoutResult(s -> history.keep(sheetId));
        }
    }

    private void asOf(String sheetId, LocalDate asOf, HttpServletRequest request, HttpServletResponse response,
            FilterChain chain) throws ServletException, IOException {
        try {
            transactions.executeWithoutResult(s -> {
                s.setRollbackOnly();
                history.putBack(sheetId, asOf);
                try {
                    chain.doFilter(request, response);
                } catch (IOException | ServletException e) {
                    throw new Failed(e);
                }
            });
        } catch (Failed f) {
            if (f.getCause() instanceof IOException e) {
                throw e;
            }
            throw (ServletException) f.getCause();
        }
    }

    private static void refuse(HttpServletResponse response, HttpStatus status, String message) throws IOException {
        response.setStatus(status.value());
        response.setContentType(MediaType.APPLICATION_JSON_VALUE);
        response.setCharacterEncoding(StandardCharsets.UTF_8.name());
        response.getWriter().write("{\"status\":" + status.value() + ",\"message\":\"" + message + "\"}");
    }

    /** A failure of the request carried out of the transaction. */
    private static final class Failed extends RuntimeException {
        Failed(Exception cause) {
            super(cause);
        }
    }
}
