package example;

import com.openai.client.OpenAIClient;
import com.openai.client.okhttp.OpenAIOkHttpClient;
import com.openai.models.responses.Response;
import com.openai.models.responses.ResponseCreateParams;
import com.openai.models.responses.EasyInputMessage;
import com.openai.models.responses.ResponseInputItem;

import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStreamReader;
import java.io.PrintStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

/** A terminal chatbot using the OpenAI Responses API. */
public final class Chatbot {
    private static final String INSTRUCTIONS = "You are a helpful assistant.";
    private final OpenAIClient client;
    private final String model;
    private final boolean stateless;
    private final List<ResponseInputItem> history = new ArrayList<>();
    private String previousResponseId;

    public Chatbot(OpenAIClient client, String model) {
        this(client, model, false);
    }

    public Chatbot(OpenAIClient client, String model, boolean stateless) {
        this.client = client;
        this.model = model;
        this.stateless = stateless;
    }

    public String reply(String message) {
        var builder = ResponseCreateParams.builder()
                .model(model)
                // Instructions must be sent again on every turn.
                .instructions(INSTRUCTIONS)
                .input(message)
                .maxOutputTokens(10_000L)
                .store(!stateless);

        if (stateless) {
            var input = new ArrayList<>(history);
            input.add(message(EasyInputMessage.Role.USER, message));
            builder.inputOfResponse(input);
        } else if (previousResponseId != null) {
            builder.previousResponseId(previousResponseId);
        }

        Response response = client.responses().create(builder.build());
        if (response.error().isPresent()) {
            throw new IllegalStateException(response.error().get().message());
        }
        if (response.status().isEmpty()
                || !"completed".equals(response.status().get().toString())) {
            throw new IllegalStateException("Response did not complete. Try again; conversation was preserved.");
        }

        // Unlike Python's output_text helper, Java exposes typed output items.
        String text = response.output().stream()
                .flatMap(item -> item.message().stream())
                .flatMap(messageOutput -> messageOutput.content().stream())
                .map(content -> content.outputText().map(output -> output.text())
                        .orElseGet(() -> content.refusal().map(refusal -> refusal.refusal()).orElse("")))
                .reduce("", String::concat)
                .strip();
        if (text.isEmpty()) {
            throw new IllegalStateException("Response contained no text. Try again; conversation was preserved.");
        }

        // Advance only after success, so a failed turn does not corrupt history.
        previousResponseId = response.id();
        if (stateless) {
            history.add(message(EasyInputMessage.Role.USER, message));
            history.add(message(EasyInputMessage.Role.ASSISTANT, text));
        }
        return text;
    }

    private static ResponseInputItem message(EasyInputMessage.Role role, String text) {
        return ResponseInputItem.ofEasyInputMessage(
                EasyInputMessage.builder().role(role).content(text).build());
    }

    public void reset() {
        previousResponseId = null;
        history.clear();
    }

    void run(BufferedReader input, PrintStream output) throws IOException {
        output.println("Stateful Chatbot (Responses API, model: " + model + ")");
        output.println("Type exit or quit to leave; /reset to start a new conversation.");
        while (true) {
            output.print("You: ");
            output.flush();
            String message = input.readLine();
            if (message == null) {
                output.println("Goodbye!");
                return;
            }
            message = message.strip();
            switch (message.toLowerCase(Locale.ROOT)) {
                case "exit", "quit" -> {
                    output.println("Goodbye!");
                    return;
                }
                case "/reset" -> {
                    reset();
                    output.println("Conversation reset.");
                }
                case "" -> { /* Ignore blank input. */ }
                default -> {
                    try {
                        output.println("Bot: " + reply(message));
                    } catch (RuntimeException exception) {
                        output.println("Error: " + exception.getMessage());
                    }
                }
            }
        }
    }

    public static void main(String[] args) throws IOException {
        ChatbotConfig config;
        try {
            config = ChatbotConfig.load(Path.of("config.properties"), System.getenv());
        } catch (IllegalArgumentException | IOException exception) {
            System.err.println("Configuration error: " + exception.getMessage());
            System.exit(1);
            return;
        }
        OpenAIClient client = OpenAIOkHttpClient.builder()
                .apiKey(config.apiKey)
                .baseUrl(config.baseUrl)
                .build();
        try {
            new Chatbot(client, config.model, config.openRouter).run(
                    new BufferedReader(new InputStreamReader(System.in, StandardCharsets.UTF_8)),
                    System.out);
        } finally {
            client.close();
        }
    }
}
