package example;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.openai.client.OpenAIClient;
import com.openai.client.okhttp.OpenAIOkHttpClient;
import com.sun.net.httpserver.HttpServer;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.io.BufferedReader;
import java.io.ByteArrayOutputStream;
import java.io.PrintStream;
import java.io.StringReader;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.List;
import java.util.Queue;

import static org.junit.jupiter.api.Assertions.*;

class ChatbotTest {
    private final ObjectMapper json = new ObjectMapper();
    private final List<JsonNode> requests = new ArrayList<>();
    private final Queue<Stub> stubs = new ArrayDeque<>();
    private HttpServer server;
    private OpenAIClient client;
    private Chatbot bot;

    private record Stub(int status, String body) { }

    @BeforeEach
    void startServer() throws Exception {
        server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        server.createContext("/v1/responses", exchange -> {
            requests.add(json.readTree(exchange.getRequestBody()));
            Stub stub = stubs.remove();
            byte[] body = stub.body().getBytes(StandardCharsets.UTF_8);
            exchange.getResponseHeaders().set("Content-Type", "application/json");
            exchange.sendResponseHeaders(stub.status(), body.length);
            try (var output = exchange.getResponseBody()) {
                output.write(body);
            }
        });
        server.start();
        client = OpenAIOkHttpClient.builder()
                .apiKey("local-test-key")
                .baseUrl("http://127.0.0.1:" + server.getAddress().getPort() + "/v1")
                .maxRetries(0)
                .build();
        bot = new Chatbot(client, "gpt-5-mini");
    }

    @AfterEach
    void stopServer() {
        client.close();
        server.stop(0);
    }

    private void respond(String id, String text, String status) throws Exception {
        stubs.add(new Stub(200, """
                {
                  "id": "%s", "object": "response", "created_at": 1,
                  "status": "%s", "model": "gpt-5-mini",
                  "output": [
                    {"type": "reasoning", "id": "rs_1", "summary": []},
                    {"type": "message", "id": "msg_1", "role": "assistant",
                     "status": "completed", "content": [
                       {"type": "output_text", "text": %s, "annotations": []}
                     ]}
                  ]
                }
                """.formatted(id, status, json.writeValueAsString(text))));
    }

    @Test
    void chainsResponsesResendsInstructionsAndResets() throws Exception {
        respond("resp_1", "Hello Alex!", "completed");
        respond("resp_2", "Your name is Alex.", "completed");
        respond("resp_3", "New conversation.", "completed");

        assertEquals("Hello Alex!", bot.reply("My name is Alex."));
        assertEquals("Your name is Alex.", bot.reply("What is my name?"));
        bot.reset();
        assertEquals("New conversation.", bot.reply("Hello"));

        assertFalse(requests.get(0).hasNonNull("previous_response_id"));
        assertEquals("resp_1", requests.get(1).path("previous_response_id").asText());
        assertEquals("What is my name?", requests.get(1).path("input").asText());
        assertFalse(requests.get(2).hasNonNull("previous_response_id"));
        for (JsonNode request : requests) {
            assertEquals("You are a helpful assistant.", request.path("instructions").asText());
            assertEquals("gpt-5-mini", request.path("model").asText());
            assertTrue(request.path("store").asBoolean());
            assertEquals(10_000, request.path("max_output_tokens").asInt());
        }
    }

    @Test
    void failedAndIncompleteTurnsKeepLastSuccessfulResponse() throws Exception {
        respond("resp_good", "Hello", "completed");
        stubs.add(new Stub(429, """
                {"error":{"message":"Rate limit reached", "type":"rate_limit_error", "code":"rate_limit_exceeded"}}
                """));
        respond("resp_incomplete", "Partial", "incomplete");
        respond("resp_empty", "", "completed");
        respond("resp_final", "Recovered", "completed");

        bot.reply("Hello");
        assertThrows(RuntimeException.class, () -> bot.reply("Failed turn"));
        assertThrows(IllegalStateException.class, () -> bot.reply("Incomplete turn"));
        assertThrows(IllegalStateException.class, () -> bot.reply("Empty turn"));
        assertEquals("Recovered", bot.reply("Try again"));
        for (JsonNode request : requests.subList(1, requests.size())) {
            assertEquals("resp_good", request.path("previous_response_id").asText());
        }
    }

    @Test
    void statelessModeReplaysTextHistoryAndResetsWithoutCommittingFailedTurns() throws Exception {
        bot = new Chatbot(client, "openai/gpt-5-mini", true);
        respond("resp_1", "Hello Alex!", "completed");
        stubs.add(new Stub(400, """
                {"error":{"message":"Test failure", "type":"invalid_request_error"}}
                """));
        respond("resp_2", "Your name is Alex.", "completed");
        respond("resp_3", "Fresh start", "completed");

        bot.reply("My name is Alex.");
        assertThrows(RuntimeException.class, () -> bot.reply("Failed turn"));
        bot.reply("What is my name?");
        bot.reset();
        bot.reply("Hello");

        for (JsonNode request : requests) {
            assertFalse(request.path("store").asBoolean(true));
            assertFalse(request.hasNonNull("previous_response_id"));
            assertEquals("openai/gpt-5-mini", request.path("model").asText());
        }
        JsonNode history = requests.get(2).path("input");
        assertEquals(3, history.size());
        assertEquals("user", history.get(0).path("role").asText());
        assertEquals("My name is Alex.", history.get(0).path("content").asText());
        assertEquals("assistant", history.get(1).path("role").asText());
        assertEquals("Hello Alex!", history.get(1).path("content").asText());
        assertEquals("What is my name?", history.get(2).path("content").asText());
        assertEquals(1, requests.get(3).path("input").size());
    }

    @Test
    void terminalSkipsBlanksAndHandlesCommandsAndEof() throws Exception {
        respond("resp_1", "Hello", "completed");
        respond("resp_2", "Fresh start", "completed");
        var bytes = new ByteArrayOutputStream();
        var output = new PrintStream(bytes, true, StandardCharsets.UTF_8);
        bot.run(new BufferedReader(new StringReader("\nHi\n/reset\nHi again\n QuIt \n")), output);
        assertEquals(2, requests.size());
        assertFalse(requests.get(1).hasNonNull("previous_response_id"));
        String transcript = bytes.toString(StandardCharsets.UTF_8);
        assertTrue(transcript.contains("Bot: Hello"));
        assertTrue(transcript.contains("Conversation reset."));
        assertTrue(transcript.contains("Goodbye!"));
        bot.run(new BufferedReader(new StringReader("exit\n")), output);
        bot.run(new BufferedReader(new StringReader("")), output);
        assertEquals(2, requests.size());
    }
}
