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

In the container's terminal, enter your API key without displaying it:

```bash
read -rsp 'API key: ' OPENAI_API_KEY
echo
export OPENAI_API_KEY
mvn -q compile exec:java
```

For OpenRouter, also set these before starting the app:

```bash
export OPENAI_BASE_URL='https://openrouter.ai/api/v1'
export OPENAI_MODEL='openai/gpt-5-mini'
```

The Linux container cannot run macOS's `security` command. Retrieve your key from
Keychain on the host and enter it at the hidden prompt above. Keys are not baked
into the container or saved by this setup, and must be set again in a new terminal.
After changing the container configuration, run **Dev Containers: Rebuild Container**.

## Run

From this directory, set your API key in the terminal and start the app:

```sh
export OPENAI_API_KEY='your-api-key'
mvn -q compile exec:java
```

The key is read from the environment, never from source code. A `.env` file is not
automatically loaded. You need an OpenAI API account with model access and billing.

The default model matches the screenshot: `gpt-5-mini`. For the direct OpenAI API,
omit the screenshot's `openai/` prefix. To use another available Responses model:

```sh
export OPENAI_MODEL='your-model-id'
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

Use the same SDK with OpenRouter's base URL and your OpenRouter key. With the key
stored in macOS Keychain under service `openrouter`, run:

```sh
export OPENAI_API_KEY="$(security find-generic-password -a "$USER" -s openrouter -w)"
export OPENAI_BASE_URL='https://openrouter.ai/api/v1'
export OPENAI_MODEL='openai/gpt-5-mini'
mvn -q compile exec:java
```

`OPENAI_API_KEY` is the SDK's environment variable even when the value is an
OpenRouter key. Do not enable shell tracing when retrieving credentials.
For this base URL the app defaults to `openai/gpt-5-mini` if `OPENAI_MODEL` is unset.
Use the documented provider-prefixed ID rather than relying on a bare alias.

OpenRouter's Responses API is stateless: it rejects `store: true` and non-null
`previous_response_id`. The app detects `openrouter.ai` and instead sends
`store: false` with locally retained user/assistant text messages on each turn.
Only successful turns enter history. `/reset` and restarting clear that history.
This mode supports the text chatbot; it does not replay tool calls or reasoning items.
See [OpenRouter Responses API](https://openrouter.ai/docs/api_reference/responses/overview).

To return to OpenAI, unset `OPENAI_BASE_URL`, set `OPENAI_MODEL='gpt-5-mini'`, and
reload the `openai` Keychain entry into `OPENAI_API_KEY`.

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
