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
import java.net.URI;
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
        String apiKey = System.getenv("OPENAI_API_KEY");
        if (apiKey == null || apiKey.isBlank()) {
            System.err.println("Set OPENAI_API_KEY before running the chatbot. See README.md.");
            System.exit(1);
        }
        String baseUrl = System.getenv("OPENAI_BASE_URL");
        boolean openRouter = baseUrl != null && !baseUrl.isBlank()
                && "openrouter.ai".equalsIgnoreCase(URI.create(baseUrl).getHost());
        String model = System.getenv("OPENAI_MODEL");
        if (model == null || model.isBlank()) {
            model = openRouter ? "openai/gpt-5-mini" : "gpt-5-mini";
        }
        OpenAIClient client = OpenAIOkHttpClient.fromEnv();
        try {
            new Chatbot(client, model, openRouter).run(
                    new BufferedReader(new InputStreamReader(System.in, StandardCharsets.UTF_8)),
                    System.out);
        } finally {
            client.close();
        }
    }
}
