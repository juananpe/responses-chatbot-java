package example;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;

class ChatbotConfigTest {
    @TempDir
    Path directory;

    @Test
    void localPropertiesOverrideEnvironmentAndSelectOpenRouter() throws Exception {
        Path file = directory.resolve("config.properties");
        Files.writeString(file, """
                OPENAI_API_KEY=local-test-key
                OPENAI_BASE_URL=https://openrouter.ai/api/v1
                OPENAI_MODEL=openai/gpt-5-mini
                """);
        ChatbotConfig config = ChatbotConfig.load(file, Map.of(
                "OPENAI_API_KEY", "environment-test-key",
                "OPENAI_BASE_URL", "https://api.openai.com/v1",
                "OPENAI_MODEL", "gpt-5-mini"));
        assertEquals("local-test-key", config.apiKey);
        assertEquals("https://openrouter.ai/api/v1", config.baseUrl);
        assertEquals("openai/gpt-5-mini", config.model);
        assertTrue(config.openRouter);
    }

    @Test
    void absentFileSupportsEnvironmentAndOpenAiDefaults() throws Exception {
        ChatbotConfig config = ChatbotConfig.load(directory.resolve("missing.properties"),
                Map.of("OPENAI_API_KEY", "test-key"));
        assertEquals("test-key", config.apiKey);
        assertEquals("https://api.openai.com/v1", config.baseUrl);
        assertEquals("gpt-5-mini", config.model);
        assertFalse(config.openRouter);
    }

    @Test
    void blankPropertiesFallBackToEnvironmentAndProviderModelDefault() throws Exception {
        Path file = directory.resolve("config.properties");
        Files.writeString(file, "OPENAI_API_KEY=\nOPENAI_MODEL=   \n");
        ChatbotConfig config = ChatbotConfig.load(file, Map.of(
                "OPENAI_API_KEY", "test-key",
                "OPENAI_BASE_URL", "https://openrouter.ai/api/v1"));
        assertEquals("test-key", config.apiKey);
        assertEquals("openai/gpt-5-mini", config.model);
        assertTrue(config.openRouter);
    }

    @Test
    void missingKeyAndInvalidUrlsFailWithActionableMessagesWithoutLeakingValues() {
        Path file = directory.resolve("missing.properties");
        var missingKey = assertThrows(IllegalArgumentException.class,
                () -> ChatbotConfig.load(file, Map.of()));
        assertTrue(missingKey.getMessage().contains("OPENAI_API_KEY"));
        for (String url : new String[]{"invalid url", "relative/path", "ftp://example.com"}) {
            var error = assertThrows(IllegalArgumentException.class,
                    () -> ChatbotConfig.load(file, Map.of(
                            "OPENAI_API_KEY", "secret-test-value", "OPENAI_BASE_URL", url)));
            assertTrue(error.getMessage().contains("OPENAI_BASE_URL"));
            assertFalse(error.getMessage().contains("secret-test-value"));
            assertFalse(error.getMessage().contains(url));
        }
    }
}
