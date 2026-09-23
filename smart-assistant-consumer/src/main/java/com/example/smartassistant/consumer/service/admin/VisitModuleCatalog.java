package com.example.smartassistant.consumer.service.admin;

import org.springframework.core.io.ClassPathResource;
import org.springframework.stereotype.Component;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.util.*;

/** Public module descriptions, shared by collection, reporting and the browser tracker. */
@Component
public class VisitModuleCatalog {
    public record Module(String code, String label, String audience, String kind, String path, String entry) {}
    private final Map<String, Module> modules = new LinkedHashMap<>();

    public VisitModuleCatalog() {
        Properties config = new Properties();
        try (var reader = new InputStreamReader(new ClassPathResource("visit-modules.properties").getInputStream(), StandardCharsets.UTF_8)) {
            config.load(reader);
        } catch (Exception error) { throw new IllegalStateException("Cannot load visit module catalog", error); }
        for (String code : config.getProperty("modules").split(",")) {
            String[] fields = config.getProperty(code).split("\\|", -1);
            if (fields.length != 5) throw new IllegalStateException("Invalid visit module: " + code);
            modules.put(code, new Module(code, fields[0], fields[1], fields[2], fields[3], fields[4]));
        }
    }
    public List<Module> all() { return List.copyOf(modules.values()); }
    public Module require(String code) {
        Module module = modules.get(code);
        if (module == null) throw new IllegalArgumentException("未知的功能模块");
        return module;
    }
}
