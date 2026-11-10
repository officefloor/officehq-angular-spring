package net.officefloor.hq.app.sheet;

import jakarta.servlet.FilterChain;
import jakarta.servlet.ReadListener;
import jakarta.servlet.ServletException;
import jakarta.servlet.ServletInputStream;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletRequestWrapper;
import jakarta.servlet.http.HttpServletResponse;
import java.io.BufferedReader;
import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.time.LocalDate;
import java.time.format.DateTimeParseException;
import java.util.Optional;
import java.util.Set;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Component;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;
import org.springframework.web.context.request.RequestContextHolder;
import org.springframework.web.context.request.ServletRequestAttributes;
import org.springframework.web.filter.OncePerRequestFilter;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

/**
 * The as-of view. A screen showing the sheet as of a date sends it on every request in the
 * {@value #HEADER} header. Such a request that only reads is answered from the sheet as it stood at
 * the end of that date (see {@link SheetHistory}), its today the date, in a transaction that is then
 * rolled back, so nothing it does is kept or audited; any other is refused (423) and changes nothing.
 * Without the header a request is served as it is, and once a change has been made the sheet is kept
 * as it now stands (taking or releasing a claim editor's lock is no change to keep).
 */
@Component
public class AsOfFilter extends OncePerRequestFilter {

    public static final String HEADER = "X-As-Of";

    /** The request attribute holding the date a request is answered as of; absent when it is answered as it is now. */
    public static final String AS_OF = AsOfFilter.class.getName() + ".asOf";

    private static final String LOAD = "/api/sheets/load";

    /**
     * The requests that only read the sheet (with every path ending in {@code /list}); saving the
     * user's locale is no change to any sheet, so it goes through as-of too.
     */
    private static final Set<String> READS = Set.of("/api/signin", LOAD, "/api/settings/get", "/api/users/locale",
            "/api/people/watched", "/api/roles/delegations", "/api/claims/tags");

    /**
     * The reads answered as the store holds the sheets now, whatever the date asked for: the
     * consolidated figures and the tree search are not dated.
     */
    private static final Set<String> UNDATED = Set.of("/api/rollup/descendants/list", "/api/search/tree/list");

    /** The changes that only take or release a claim editor's lock: not part of the sheet's history. */
    private static final Set<String> LOCKS = Set.of("/api/claims/lock", "/api/claims/unlock");

    private final SheetHistory history;
    private final SheetService sheets;
    private final TransactionTemplate rolledBack;
    private final JsonMapper json;

    public AsOfFilter(SheetHistory history, SheetService sheets, PlatformTransactionManager transactions,
            JsonMapper json) {
        this.history = history;
        this.sheets = sheets;
        this.rolledBack = new TransactionTemplate(transactions);
        this.json = json;
    }

    @Override
    protected boolean shouldNotFilter(HttpServletRequest request) {
        return !"POST".equals(request.getMethod()) || !request.getRequestURI().startsWith("/api/");
    }

    @Override
    protected void doFilterInternal(HttpServletRequest request, HttpServletResponse response, FilterChain chain)
            throws ServletException, IOException {
        Body body = new Body(request);
        String path = request.getRequestURI();
        boolean read = READS.contains(path) || path.endsWith("/list");
        String sheet = body.sheet();
        String header = request.getHeader(HEADER);
        LocalDate asOf = null;
        if (header != null && !header.isBlank()) {
            try {
                asOf = LocalDate.parse(header.trim());
            } catch (DateTimeParseException e) {
                response.sendError(HttpStatus.BAD_REQUEST.value(), "Not a date: " + header);
                return;
            }
        }
        if (asOf == null) {
            chain.doFilter(body, response);
            if (sheet != null && path.equals(LOAD)) {
                history.captureFirst(sheet);
            } else if (sheet != null && !read && !LOCKS.contains(path)) {
                history.capture(sheet);
            }
            return;
        }
        if (!read) {
            response.sendError(HttpStatus.LOCKED.value(), "The sheet is shown as of a date and may not be changed.");
            return;
        }
        if (sheet == null || UNDATED.contains(path)) {
            chain.doFilter(body, response);
            return;
        }
        if (path.equals(LOAD)) {
            // a sheet never loaded starts as usual, and counts from the beginning of time
            String user = body.field("user");
            sheets.load(sheet, user == null || user.isBlank() ? "?" : user.trim());
            history.captureFirst(sheet);
        }
        LocalDate date = asOf;
        body.setAttribute(AS_OF, date);
        try {
            rolledBack.executeWithoutResult(status -> {
                status.setRollbackOnly();
                history.restore(sheet, date);
                try {
                    chain.doFilter(body, response);
                } catch (IOException | ServletException e) {
                    throw new IllegalStateException(e);
                }
            });
        } catch (IllegalStateException e) {
            if (e.getCause() instanceof IOException io) {
                throw io;
            }
            if (e.getCause() instanceof ServletException se) {
                throw se;
            }
            throw e;
        }
    }

    /**
     * The date the current request is answered as of; empty when it is answered as it is now. Only the
     * sheet the request is about is restored as it stood then: what another sheet held then must be
     * read from its own history with this date.
     */
    public static Optional<LocalDate> current() {
        return RequestContextHolder.getRequestAttributes() instanceof ServletRequestAttributes attrs
                && attrs.getRequest().getAttribute(AS_OF) instanceof LocalDate date ? Optional.of(date) : Optional.empty();
    }

    /** The request with its body read once, so both this filter and the handler can read it. */
    private final class Body extends HttpServletRequestWrapper {

        private final byte[] bytes;
        private JsonNode node;

        Body(HttpServletRequest request) throws IOException {
            super(request);
            this.bytes = request.getInputStream().readAllBytes();
        }

        /** The sheet the request is about: its {@code sheet}, or {@code id} for the sheet's own requests. */
        String sheet() {
            String sheet = field("sheet");
            if (sheet == null && getRequestURI().startsWith("/api/sheets/")) {
                sheet = field("id");
            }
            return sheet == null || sheet.isBlank() ? null : sheet.trim();
        }

        String field(String name) {
            if (node == null) {
                try {
                    node = json.readTree(bytes);
                } catch (RuntimeException e) {
                    return null;
                }
            }
            JsonNode value = node == null ? null : node.get(name);
            return value == null || !value.isString() ? null : value.asString();
        }

        @Override
        public ServletInputStream getInputStream() {
            ByteArrayInputStream in = new ByteArrayInputStream(bytes);
            return new ServletInputStream() {
                @Override
                public int read() {
                    return in.read();
                }

                @Override
                public int read(byte[] b, int off, int len) {
                    return in.read(b, off, len);
                }

                @Override
                public boolean isFinished() {
                    return in.available() == 0;
                }

                @Override
                public boolean isReady() {
                    return true;
                }

                @Override
                public void setReadListener(ReadListener listener) {
                    throw new UnsupportedOperationException();
                }
            };
        }

        @Override
        public BufferedReader getReader() {
            return new BufferedReader(new InputStreamReader(getInputStream(), StandardCharsets.UTF_8));
        }
    }
}
