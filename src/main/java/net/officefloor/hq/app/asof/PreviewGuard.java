package net.officefloor.hq.app.asof;

import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import java.sql.Date;
import java.time.LocalDate;
import java.time.format.DateTimeParseException;
import java.util.Map;
import javax.sql.DataSource;
import org.springframework.context.annotation.Configuration;
import org.springframework.http.HttpMethod;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.TransactionStatus;
import org.springframework.transaction.support.DefaultTransactionDefinition;
import org.springframework.web.method.HandlerMethod;
import org.springframework.web.servlet.HandlerInterceptor;
import org.springframework.web.servlet.HandlerMapping;
import org.springframework.web.servlet.config.annotation.InterceptorRegistry;
import org.springframework.web.servlet.config.annotation.WebMvcConfigurer;

/**
 * The preview date, which belongs to the user's screen: a read of a sheet carrying an
 * {@value #HEADER} date is shown over the sheet's current records as if its today were that date.
 * A change ignores it: every change uses the sheet's real today.
 *
 * <p>A read with a preview date runs in a transaction that sets the sheet's today to it (nothing a
 * change of today does happens: no snapshots, scores or auto-closing), rolled back once the read is
 * answered, so it changes nothing and audits nothing. Under an as-of view (see {@link AsOfGuard}) it
 * joins the as-of transaction and moves the restored sheet's today.
 */
@Configuration
public class PreviewGuard implements WebMvcConfigurer, HandlerInterceptor {

    /** The request header carrying the user's preview date (yyyy-mm-dd). */
    public static final String HEADER = "X-Preview";

    private static final String VIEW = PreviewGuard.class.getName() + ".view";

    private final JdbcTemplate jdbc;
    private final PlatformTransactionManager transactions;

    public PreviewGuard(DataSource dataSource, PlatformTransactionManager transactions) {
        this.jdbc = new JdbcTemplate(dataSource);
        this.transactions = transactions;
    }

    @Override
    public void addInterceptors(InterceptorRegistry registry) {
        // after the as-of guard, so a preview moves the today of a sheet it has put back
        registry.addInterceptor(this).addPathPatterns("/api/sheets/*/**").order(1);
    }

    @Override
    public boolean preHandle(HttpServletRequest request, HttpServletResponse response, Object handler) {
        if (!(handler instanceof HandlerMethod method)) return true;
        String sheetId = sheetId(request);
        String header = request.getHeader(HEADER);
        if (sheetId == null || header == null || header.isBlank()) return true;
        if (!isRead(request) && !method.hasMethodAnnotation(AsOfRead.class)) return true;
        LocalDate preview;
        try {
            preview = LocalDate.parse(header.trim());
        } catch (DateTimeParseException e) {
            return true;
        }
        TransactionStatus transaction = transactions.getTransaction(new DefaultTransactionDefinition());
        try {
            jdbc.update("UPDATE sheet SET today = ? WHERE id = ?", Date.valueOf(preview), sheetId);
        } catch (RuntimeException e) {
            transactions.rollback(transaction);
            throw e;
        }
        request.setAttribute(VIEW, transaction);
        return true;
    }

    @Override
    public void afterCompletion(HttpServletRequest request, HttpServletResponse response, Object handler, Exception ex) {
        if (request.getAttribute(VIEW) instanceof TransactionStatus transaction) {
            request.removeAttribute(VIEW);
            transactions.rollback(transaction);
        }
    }

    private static boolean isRead(HttpServletRequest request) {
        return HttpMethod.GET.matches(request.getMethod()) || HttpMethod.HEAD.matches(request.getMethod());
    }

    /** The sheet the request is for: its sheetId path variable, or the id of a request to the sheet itself. */
    private static String sheetId(HttpServletRequest request) {
        @SuppressWarnings("unchecked")
        Map<String, String> vars = (Map<String, String>) request.getAttribute(HandlerMapping.URI_TEMPLATE_VARIABLES_ATTRIBUTE);
        if (vars == null) return null;
        return vars.getOrDefault("sheetId", vars.get("id"));
    }
}
