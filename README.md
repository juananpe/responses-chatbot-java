# Java Responses API chatbot

A command-line equivalent of the Python example, using the official OpenAI Java SDK.
Requires Java 17+ and Maven.

## VS Code dev container

Install Docker Desktop (or a compatible running Docker engine) and the VS Code
[Dev Containers extension](https://marketplace.visualstudio.com/items?itemName=ms-vscode-remote.remote-containers).
Open this project folder in VS Code and run **Dev Containers: Reopen in Container**
from the Command Palette.

The container provides Java 17, Maven 3.9.11, and the Java Extension Pack (including
debugging, Maven support, and the test runner). On creation it downloads Maven
dependencies and plugins, compiles the project, and runs the local tests. The first
setup needs internet access; no host Java or Maven installation is needed.

After the container finishes setting up, follow the configuration steps below in
its terminal. The project folder is mounted into the container, so the same
`config.properties` works on the host and inside VS Code.
After changing the container configuration, run **Dev Containers: Rebuild Container**.

## Configure and run

Copy the example file in the project root:

```sh
cp config.properties.example config.properties
```

Edit `config.properties` and paste your OpenRouter key:

```properties
OPENAI_API_KEY=your-openrouter-api-key
OPENAI_BASE_URL=https://openrouter.ai/api/v1
OPENAI_MODEL=openai/gpt-5-mini
```

Use plain values without quotes. The app reads this UTF-8 file from its current
working directory. Run from the project root:

```sh
mvn -q compile exec:java
```

`config.properties` contains your key in plain text and is ignored by Git. Only the
empty-key example is published. The Linux dev container cannot access macOS
Keychain; paste your key into the local file on the host or in the container editor.

For direct OpenAI, use your OpenAI key with:

```properties
OPENAI_API_KEY=your-openai-api-key
OPENAI_BASE_URL=https://api.openai.com/v1
OPENAI_MODEL=gpt-5-mini
```

For each setting, a nonblank value in `config.properties` takes precedence over
the matching environment variable. Missing or blank values fall back to the
environment, then defaults: OpenAI's base URL and `gpt-5-mini`, or
`openai/gpt-5-mini` when the base URL points to `openrouter.ai`. The API key is
required. `.env` files are not loaded. No new library is needed to read properties.

Environment-only configuration still works if `config.properties` is absent:

```sh
export OPENAI_API_KEY='your-api-key'
mvn -q compile exec:java
```

Example conversation:

```text
You: My name is Alex.
Bot: Nice to meet you, Alex!
You: What is my name?
Bot: Your name is Alex.
You: /reset
Conversation reset.
You: exit
Goodbye!
```

## OpenRouter

The example properties file is preconfigured for OpenRouter. Use the documented
model ID `openai/gpt-5-mini`. `OPENAI_API_KEY` holds your OpenRouter key when the
base URL points to OpenRouter.

OpenRouter's Responses API is stateless: it rejects `store: true` and non-null
`previous_response_id`. The app detects `openrouter.ai` and instead sends
`store: false` with locally retained user/assistant text messages on each turn.
Only successful turns enter history. `/reset` and restarting clear that history.
This mode supports the text chatbot; it does not replay tool calls or reasoning items.
See [OpenRouter Responses API](https://openrouter.ai/docs/api_reference/responses/overview).

## How OpenAI memory works

`Chatbot.reply()` calls `client.responses().create(...)`. It saves the successful
response ID and sends it as `previousResponseId` with the next user message. This
lets OpenAI supply the earlier context instead of maintaining a local message list.
The initial instructions are sent on every request because chaining does not carry
over top-level instructions.

Responses are stored on OpenAI (`store(true)`). `/reset` drops the local reference
and starts a new chain; it does not delete previously stored responses. The app does
not save IDs to disk, so restarting starts a fresh conversation. Earlier context is
still billed as input on subsequent turns, and long chats can reach context limits.

The program skips blank input, handles API errors, and keeps the last successful
conversation state when a request fails or produces an incomplete response. Both
`exit` and `quit` are case insensitive; end-of-input also exits.

To change the chatbot's behavior, edit `INSTRUCTIONS` in
`src/main/java/example/Chatbot.java`.

## Verification

```sh
mvn test
```

Tests use a local HTTP server with simulated Responses payloads. They require no
API key and make no OpenAI calls.

Official references:
- [OpenAI Java SDK](https://developers.openai.com/api/reference/java)
- [Conversation state](https://developers.openai.com/api/docs/guides/conversation-state)
- [GPT-5 Mini](https://developers.openai.com/api/docs/models/gpt-5-mini)
