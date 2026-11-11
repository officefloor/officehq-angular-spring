package net.officefloor.hq.app.asof;

import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import java.io.IOException;
import java.net.URLDecoder;
import java.nio.charset.StandardCharsets;
import java.time.LocalDate;
import java.time.format.DateTimeParseException;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.locks.ReentrantLock;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import net.officefloor.hq.app.sheet.SheetService;
import org.springframework.http.HttpMethod;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.stereotype.Component;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;
import org.springframework.web.filter.OncePerRequestFilter;

/**
 * The as-of view. A screen showing a sheet as of a date sends the date in the {@code X-As-Of} header with every
 * request: a read (and loading the sheet) is answered from the sheet as it stood at the end of that date, inside a
 * transaction that is rolled back; every change is refused. Without the header, each change that went through is
 * followed by a copy of how the sheet then stands, under its today.
 */
@Component
public class AsOfFilter extends OncePerRequestFilter {

    public static final String HEADER = "X-As-Of";

    private static final Pattern SHEET_PATH = Pattern.compile("^/api/sheets/([^/]+)(/.*)?$");

    private final SheetSnapshotService snapshots;
    private final SheetService sheets;
    private final TransactionTemplate rolledBack;
    /** One as-of read of a sheet at a time: each puts the whole sheet back in its own transaction. */
    private final ConcurrentHashMap<String, ReentrantLock> locks = new ConcurrentHashMap<>();

    public AsOfFilter(SheetSnapshotService snapshots, SheetService sheets, PlatformTransactionManager transactions) {
        this.snapshots = snapshots;
        this.sheets = sheets;
        this.rolledBack = new TransactionTemplate(transactions);
    }

    @Override
    protected void doFilterInternal(HttpServletRequest request, HttpServletResponse response, FilterChain chain)
            throws ServletException, IOException {
        Matcher m = SHEET_PATH.matcher(request.getRequestURI().substring(request.getContextPath().length()));
        if (!m.matches()) {
            chain.doFilter(request, response);
            return;
        }
        String sheetId = URLDecoder.decode(m.group(1), StandardCharsets.UTF_8).trim();
        String rest = m.group(2) == null ? "" : m.group(2);
        boolean read = HttpMethod.GET.matches(request.getMethod()) || HttpMethod.HEAD.matches(request.getMethod())
                || HttpMethod.OPTIONS.matches(request.getMethod());
        boolean load = rest.equals("/load");
        String header = request.getHeader(HEADER);
        if (header == null || header.isBlank()) {
            chain.doFilter(request, response);
            if ((!read || load) && response.getStatus() < 400) {
                snapshots.capture(sheetId);
            }
            return;
        }
        LocalDate asOf;
        try {
            asOf = LocalDate.parse(header.trim());
        } catch (DateTimeParseException e) {
            refuse(response, HttpStatus.BAD_REQUEST, "The as-of date " + header.trim() + " is not a date");
            return;
        }
        if (!read && !load) {
            refuse(response, HttpStatus.CONFLICT, "Sheet " + sheetId + " is shown as of " + asOf
                    + " and may not be changed");
            return;
        }
        if (load && !snapshots.exists(sheetId)) {
            String user = request.getHeader("X-User");
            if (user == null || user.isBlank()) {
                chain.doFilter(request, response);
                return;
            }
            sheets.load(sheetId, user.trim());
            snapshots.capture(sheetId);
        }
        ReentrantLock lock = locks.computeIfAbsent(sheetId, k -> new ReentrantLock());
        lock.lock();
        try {
            Exception failed = rolledBack.execute(status -> {
                status.setRollbackOnly();
                snapshots.restore(sheetId, asOf);
                AsOfContext.set(asOf);
                try {
                    chain.doFilter(request, response);
                    return null;
                } catch (IOException | ServletException e) {
                    return e;
                } finally {
                    AsOfContext.clear();
                }
            });
            if (failed instanceof IOException e) {
                throw e;
            }
            if (failed instanceof ServletException e) {
                throw e;
            }
        } finally {
            lock.unlock();
        }
    }

    private static void refuse(HttpServletResponse response, HttpStatus status, String message) throws IOException {
        response.setStatus(status.value());
        response.setContentType(MediaType.APPLICATION_JSON_VALUE);
        response.getWriter().write("{\"message\":\"" + message.replace("\\", "\\\\").replace("\"", "\\\"") + "\"}");
    }
}
