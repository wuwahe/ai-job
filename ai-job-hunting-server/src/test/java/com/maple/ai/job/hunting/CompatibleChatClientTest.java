package com.maple.ai.job.hunting;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.maple.ai.job.hunting.common.ai.CompatibleChatClient;
import com.maple.ai.job.hunting.frame.exp.ApplicationException;
import com.maple.ai.job.hunting.model.bo.UserAIConfigDO;
import com.maple.ai.job.hunting.model.openai.OpenaiMessage;
import com.maple.ai.job.hunting.service.biz.UserAIConfigService;
import com.sun.net.httpserver.Headers;
import com.sun.net.httpserver.HttpServer;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.ai.chat.messages.MessageType;
import org.springframework.ai.chat.prompt.Prompt;

import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.*;

class CompatibleChatClientTest {
    private static final ObjectMapper JSON = new ObjectMapper();
    private HttpServer server;
    private UserAIConfigDO config;
    private volatile JsonNode requestBody;
    private volatile Headers requestHeaders;
    private volatile String requestPath;
    private volatile String response = "{\"choices\":[{\"message\":{\"content\":\"test answer\"}}]}";
    private volatile int status = 200;
    private volatile long delayMillis;
    private final AtomicInteger requests = new AtomicInteger();

    @BeforeEach
    void startLocalEndpoint() throws Exception {
        server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        server.createContext("/", exchange -> {
            requests.incrementAndGet();
            requestHeaders = exchange.getRequestHeaders();
            requestPath = exchange.getRequestURI().getPath();
            requestBody = JSON.readTree(exchange.getRequestBody());
            // Delay only the timeout test; no external endpoint is contacted.
            if (delayMillis > 0) {
                try {
                    Thread.sleep(delayMillis);
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                }
            }
            byte[] bytes = response.getBytes(StandardCharsets.UTF_8);
            exchange.getResponseHeaders().set("Content-Type", "application/json");
            try {
                exchange.sendResponseHeaders(status, bytes.length);
                exchange.getResponseBody().write(bytes);
            } finally {
                exchange.close();
            }
        });
        server.start();
        config = new UserAIConfigDO();
        config.setProvider(0);
        config.setBaseUrl("http://127.0.0.1:" + server.getAddress().getPort() + "/v1/");
        config.setModelName("vendor/arbitrary-model");
        config.setApiKey("test-placeholder");
        config.setTimeout(5);
    }

    @AfterEach
    void stopLocalEndpoint() {
        server.stop(0);
    }

    @Test
    void legacyConfigurationUsesOpenAiAndPreservesAllConversationTurns() {
        String result = new CompatibleChatClient(config).call(conversation()).getResult().getOutput().getContent();
        assertEquals("test answer", result);
        assertEquals("/v1/chat/completions", requestPath);
        assertEquals("Bearer test-placeholder", requestHeaders.getFirst("Authorization"));
        assertNull(requestHeaders.getFirst("x-api-key"));
        assertEquals("vendor/arbitrary-model", requestBody.path("model").asText());
        assertEquals(4, requestBody.path("messages").size());
        assertEquals("system", requestBody.path("messages").path(0).path("role").asText());
        assertEquals("latest question", requestBody.path("messages").path(3).path("content").asText());
        assertFalse(requestBody.path("stream").asBoolean());
        assertFalse(requestBody.has("max_tokens"));
        assertFalse(requestBody.has("temperature"));
    }

    @Test
    void anthropicMovesSystemPromptAndParsesOnlyTextBlocks() {
        config.setApiFormat("ANTHROPIC");
        config.setMaxTokens(4096);
        response = "{\"content\":[{\"type\":\"thinking\",\"thinking\":\"hidden\"},"
                + "{\"type\":\"text\",\"text\":\"你好\"},{\"type\":\"text\",\"text\":\" 😀\"}]}";
        String result = new CompatibleChatClient(config).call(conversation()).getResult().getOutput().getContent();
        assertEquals("你好 😀", result);
        assertEquals("/v1/messages", requestPath);
        assertEquals("test-placeholder", requestHeaders.getFirst("x-api-key"));
        assertEquals("2023-06-01", requestHeaders.getFirst("anthropic-version"));
        assertNull(requestHeaders.getFirst("Authorization"));
        assertEquals("instructions", requestBody.path("system").asText());
        assertEquals(4096, requestBody.path("max_tokens").asInt());
        assertEquals(3, requestBody.path("messages").size());
        assertEquals("latest question", requestBody.path("messages").path(2).path("content").asText());
    }

    @ParameterizedTest
    @ValueSource(strings = {"OPENAI", "ANTHROPIC"})
    void configurationTestUsesSelectedProtocolAndCustomPath(String format) {
        config.setApiFormat(format);
        config.setCompletionsPath("/gateway/chat");
        response = format.equals("ANTHROPIC")
                ? "{\"content\":[{\"type\":\"text\",\"text\":\"test answer\"}]}" : response;
        assertEquals("test answer", new UserAIConfigService().testUserApiConfig(config));
        assertEquals("/v1/gateway/chat", requestPath);
        if (format.equals("ANTHROPIC")) {
            assertEquals(1024, requestBody.path("max_tokens").asInt());
        }
    }

    @ParameterizedTest
    @ValueSource(ints = {301, 401, 429, 500})
    void upstreamFailuresAreSanitizedAndNeverRetried(int errorStatus) {
        status = errorStatus;
        response = "{\"error\":\"private-upstream-body test-placeholder\"}";
        ApplicationException error = assertThrows(ApplicationException.class,
                () -> new CompatibleChatClient(config).call("question"));
        assertTrue(error.getMessage().contains("HTTP " + errorStatus));
        assertFalse(error.getMessage().contains("private-upstream-body"));
        assertFalse(error.getMessage().contains("test-placeholder"));
        assertNull(error.getCause());
        assertEquals(1, requests.get());
    }

    @ParameterizedTest
    @ValueSource(strings = {"not-json-private-body", "{}", "null", "{\"choices\":[]}",
            "{\"choices\":[{\"message\":{\"content\":\"\"}}]}"})
    void invalidOrEmptyResponsesFailWithoutExposingBody(String body) {
        response = body;
        ApplicationException error = assertThrows(ApplicationException.class,
                () -> new CompatibleChatClient(config).call("question"));
        assertFalse(error.getMessage().contains("private-body"));
        assertNull(error.getCause());
        assertEquals(1, requests.get());
    }

    @Test
    void timeoutIsBoundedAndNotRetried() {
        config.setTimeout(1);
        delayMillis = 1500;
        ApplicationException error = assertThrows(ApplicationException.class,
                () -> new CompatibleChatClient(config).call("question"));
        assertTrue(error.getMessage().contains("超时"));
        assertEquals(1, requests.get());
    }

    @Test
    void invalidConfigurationFailsBeforeNetworkAccess() {
        config.setApiFormat("unsupported");
        assertThrows(ApplicationException.class, () -> new CompatibleChatClient(config));
        config.setApiFormat("ANTHROPIC");
        config.setMaxTokens(0);
        assertThrows(ApplicationException.class, () -> new CompatibleChatClient(config));
        config.setMaxTokens(1024);
        config.setCompletionsPath("https://elsewhere.example/messages");
        assertThrows(ApplicationException.class, () -> new CompatibleChatClient(config));
        config.setCompletionsPath("/messages");
        config.setBaseUrl("https://user:password@example.com/v1");
        assertThrows(ApplicationException.class, () -> new CompatibleChatClient(config));
        assertEquals(0, requests.get());
    }

    @Test
    void cachedClientDetectsProtocolModelAndTokenLimitChanges() {
        CompatibleChatClient cached = new CompatibleChatClient(config);
        assertTrue(cached.matches(config));
        config.setApiFormat("ANTHROPIC");
        assertFalse(cached.matches(config));
        config.setApiFormat("OPENAI");
        config.setModelName("other-model");
        assertFalse(cached.matches(config));
        config.setModelName("vendor/arbitrary-model");
        config.setMaxTokens(2048);
        assertFalse(cached.matches(config));
    }

    private Prompt conversation() {
        return new Prompt(List.of(
                new OpenaiMessage(MessageType.SYSTEM, "instructions"),
                new OpenaiMessage(MessageType.USER, "first question"),
                new OpenaiMessage(MessageType.ASSISTANT, "first answer"),
                new OpenaiMessage(MessageType.USER, "latest question")));
    }
}
