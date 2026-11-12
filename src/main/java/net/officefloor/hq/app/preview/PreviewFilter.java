package net.officefloor.hq.app.preview;

import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import java.io.IOException;
import java.net.URLDecoder;
import java.nio.charset.StandardCharsets;
import java.time.LocalDate;
import java.time.format.DateTimeParseException;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import net.officefloor.hq.app.asof.AsOfFilter;
import org.springframework.http.HttpMethod;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import javax.sql.DataSource;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Component;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;
import org.springframework.web.filter.OncePerRequestFilter;

/**
 * The preview date. A screen previewing its sheet on a date sends the date in the {@code X-Preview} header with every
 * request: a read is answered over the sheet's current records as if its today were that date, inside a transaction
 * that is rolled back, so nothing a change of today does happens and nothing is kept. A change is made as without the
 * header, under the sheet's real today. An as-of view takes precedence over a preview.
 */
@Component
public class PreviewFilter extends OncePerRequestFilter {

    public static final String HEADER = "X-Preview";

    private static final Pattern SHEET_PATH = Pattern.compile("^/api/sheets/([^/]+)(/.*)?$");

    private final JdbcTemplate jdbc;
    private final TransactionTemplate rolledBack;

    public PreviewFilter(DataSource dataSource, PlatformTransactionManager transactions) {
        this.jdbc = new JdbcTemplate(dataSource);
        this.rolledBack = new TransactionTemplate(transactions);
    }

    @Override
    protected void doFilterInternal(HttpServletRequest request, HttpServletResponse response, FilterChain chain)
            throws ServletException, IOException {
        Matcher m = SHEET_PATH.matcher(request.getRequestURI().substring(request.getContextPath().length()));
        String header = request.getHeader(HEADER);
        String asOf = request.getHeader(AsOfFilter.HEADER);
        boolean read = HttpMethod.GET.matches(request.getMethod()) || HttpMethod.HEAD.matches(request.getMethod());
        if (!m.matches() || !read || header == null || header.isBlank() || (asOf != null && !asOf.isBlank())) {
            chain.doFilter(request, response);
            return;
        }
        String sheetId = URLDecoder.decode(m.group(1), StandardCharsets.UTF_8).trim();
        LocalDate preview;
        try {
            preview = LocalDate.parse(header.trim());
        } catch (DateTimeParseException e) {
            response.setStatus(HttpStatus.BAD_REQUEST.value());
            response.setContentType(MediaType.APPLICATION_JSON_VALUE);
            response.getWriter().write("{\"message\":\"The preview date is not a date\"}");
            return;
        }
        Exception failed = rolledBack.execute(status -> {
            status.setRollbackOnly();
            jdbc.update("UPDATE sheet SET today = ? WHERE id = ?", preview, sheetId);
            PreviewContext.set(preview);
            try {
                chain.doFilter(request, response);
                return null;
            } catch (IOException | ServletException e) {
                return e;
            } finally {
                PreviewContext.clear();
            }
        });
        if (failed instanceof IOException e) {
            throw e;
        }
        if (failed instanceof ServletException e) {
            throw e;
        }
    }
}
