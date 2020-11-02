package be.rubus.payara.accelerator.k8s.operator;

import org.thymeleaf.TemplateEngine;
import org.thymeleaf.context.Context;
import org.thymeleaf.templateresolver.ClassLoaderTemplateResolver;

import java.io.StringWriter;
import java.util.Map;

public final class ThymeleafEngine {

    private static ThymeleafEngine INSTANCE;

    private TemplateEngine engine;

    private ThymeleafEngine() {
        ClassLoaderTemplateResolver resolver = new ClassLoaderTemplateResolver();
        resolver.setTemplateMode("TEXT");
        engine = new TemplateEngine();
        engine.setTemplateResolver(resolver);

    }

    public String processFile(String file, Map<String, String> variables) {
        StringWriter writer = new StringWriter();
        Context context = new Context();

        for (Map.Entry<String, String> variable : variables.entrySet()) {
            context.setVariable(variable.getKey(), variable.getValue());
        }
        engine.process(file, context, writer);

        return writer.toString();

    }
    public static ThymeleafEngine getInstance() {
        if (INSTANCE == null) {
            INSTANCE = new ThymeleafEngine();
        }
        return INSTANCE;
    }
}
