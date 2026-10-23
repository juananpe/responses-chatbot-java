package example;

import java.io.IOException;
import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Map;
import java.util.Properties;

/** Loads local settings without including credentials in logs or toString(). */
final class ChatbotConfig {
    final String apiKey;
    final String baseUrl;
    final String model;
    final boolean openRouter;

    private ChatbotConfig(String apiKey, String baseUrl, String model, boolean openRouter) {
        this.apiKey = apiKey;
        this.baseUrl = baseUrl;
        this.model = model;
        this.openRouter = openRouter;
    }

    static ChatbotConfig load(Path file, Map<String, String> environment) throws IOException {
        Properties properties = new Properties();
        if (Files.exists(file)) {
            try (var reader = Files.newBufferedReader(file, StandardCharsets.UTF_8)) {
                properties.load(reader);
            }
        }
        String apiKey = setting(properties, environment, "OPENAI_API_KEY", "");
        if (apiKey.isBlank()) {
            throw new IllegalArgumentException(
                    "Set OPENAI_API_KEY in config.properties or the environment. See README.md.");
        }
        String baseUrl = setting(properties, environment, "OPENAI_BASE_URL", "https://api.openai.com/v1");
        URI uri;
        try {
            uri = URI.create(baseUrl);
        } catch (IllegalArgumentException exception) {
            throw new IllegalArgumentException("OPENAI_BASE_URL must be an absolute HTTP(S) URL.");
        }
        if (uri.getHost() == null || !("https".equalsIgnoreCase(uri.getScheme())
                || "http".equalsIgnoreCase(uri.getScheme()))) {
            throw new IllegalArgumentException("OPENAI_BASE_URL must be an absolute HTTP(S) URL.");
        }
        boolean openRouter = "openrouter.ai".equalsIgnoreCase(uri.getHost());
        String model = setting(properties, environment, "OPENAI_MODEL",
                openRouter ? "openai/gpt-5-mini" : "gpt-5-mini");
        return new ChatbotConfig(apiKey, baseUrl, model, openRouter);
    }

    private static String setting(Properties properties, Map<String, String> environment,
                                  String key, String fallback) {
        String value = properties.getProperty(key);
        if (value == null || value.isBlank()) {
            value = environment.get(key);
        }
        return value == null || value.isBlank() ? fallback : value.strip();
    }
}
