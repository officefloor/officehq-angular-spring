package net.officefloor.hq.app;

import net.officefloor.hq.app.asof.AsOfGuard;
import net.officefloor.hq.app.preview.PreviewGuard;
import net.officefloor.hq.app.settings.ViewerGuard;
import org.springframework.context.annotation.Configuration;
import org.springframework.web.servlet.config.annotation.InterceptorRegistry;
import org.springframework.web.servlet.config.annotation.WebMvcConfigurer;

/**
 * Guards the API: a screen showing a sheet as of a date is answered as of it and refused every change; a viewer, or
 * anyone on an archived sheet, is refused every change; a screen previewing a sheet on a date reads it as if that
 * were its today.
 */
@Configuration
public class WebConfig implements WebMvcConfigurer {

    private final AsOfGuard asOfGuard;
    private final ViewerGuard viewerGuard;
    private final PreviewGuard previewGuard;

    public WebConfig(AsOfGuard asOfGuard, ViewerGuard viewerGuard, PreviewGuard previewGuard) {
        this.asOfGuard = asOfGuard;
        this.viewerGuard = viewerGuard;
        this.previewGuard = previewGuard;
    }

    @Override
    public void addInterceptors(InterceptorRegistry registry) {
        registry.addInterceptor(asOfGuard).addPathPatterns("/api/**");
        registry.addInterceptor(viewerGuard).addPathPatterns("/api/**");
        registry.addInterceptor(previewGuard).addPathPatterns("/api/**");
    }
}
