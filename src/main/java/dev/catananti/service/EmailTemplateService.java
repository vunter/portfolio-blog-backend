package dev.catananti.service;

import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.thymeleaf.TemplateEngine;
import org.thymeleaf.context.Context;
import org.thymeleaf.templatemode.TemplateMode;
import org.thymeleaf.templateresolver.ClassLoaderTemplateResolver;

import java.util.Map;

/**
 * F-172: Thymeleaf-based email template renderer.
 * <p>
 * Standalone configuration — does NOT interfere with WebFlux (no auto-configured
 * view resolver). Templates live under {@code classpath:/templates/email/}.
 * </p>
 */
@Service
@Slf4j
public class EmailTemplateService {

    private final TemplateEngine templateEngine;

    public EmailTemplateService() {
        var resolver = new ClassLoaderTemplateResolver();
        resolver.setPrefix("templates/email/");
        resolver.setSuffix(".html");
        resolver.setTemplateMode(TemplateMode.HTML);
        resolver.setCharacterEncoding("UTF-8");
        resolver.setCacheable(true);

        this.templateEngine = new TemplateEngine();
        this.templateEngine.setTemplateResolver(resolver);
        log.info("EmailTemplateService initialized with Thymeleaf (templates/email/)");
    }

    /**
     * Render an email template with the given variables.
     *
     * @param templateName the template file name (without .html extension)
     * @param variables    key-value pairs available in the template via {@code ${key}}
     * @return the rendered HTML string
     */
    public String render(String templateName, Map<String, Object> variables) {
        var context = new Context();
        context.setVariables(variables);
        return templateEngine.process(templateName, context);
    }
}
