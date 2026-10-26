package com.maple.ai.job.hunting.common.ai;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.maple.ai.job.hunting.emums.AIFormatEnum;
import com.maple.ai.job.hunting.emums.AIProviderEnum;
import com.maple.ai.job.hunting.frame.exp.ApplicationException;
import com.maple.ai.job.hunting.model.bo.UserAIConfigDO;
import org.apache.commons.lang3.StringUtils;
import org.springframework.ai.chat.ChatClient;
import org.springframework.ai.chat.ChatResponse;
import org.springframework.ai.chat.Generation;
import org.springframework.ai.chat.messages.Message;
import org.springframework.ai.chat.messages.MessageType;
import org.springframework.ai.chat.prompt.Prompt;
import org.springframework.http.MediaType;
import org.springframework.http.client.JdkClientHttpRequestFactory;
import org.springframework.web.client.ResourceAccessException;
import org.springframework.web.client.RestClient;
import org.springframework.web.client.RestClientException;

import java.net.URI;
import java.net.http.HttpClient;
import java.time.Duration;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;

/**
 * Text-only chat adapter for OpenAI-compatible and Anthropic-compatible endpoints.
 */
public final class CompatibleChatClient implements ChatClient {
    private static final ObjectMapper JSON = new ObjectMapper();
    // Share connection resources; never redirect credentials to another endpoint.
    private static final HttpClient HTTP_CLIENT = HttpClient.newBuilder()
            .connectTimeout(Duration.ofSeconds(5)).followRedirects(HttpClient.Redirect.NEVER).build();

    private final AIFormatEnum format;
    private final URI endpoint;
    private final String model;
    private final String apiKey;
    private final int timeout;
    private final int maxTokens;
    private final RestClient client;

    public CompatibleChatClient(UserAIConfigDO config) {
        validate(config);
        format = AIFormatEnum.resolve(config.getApiFormat());
        endpoint = resolveEndpoint(config);
        model = config.getModelName().trim();
        apiKey = config.getApiKey().trim();
        timeout = config.getTimeout() == null ? 60 : config.getTimeout();
        maxTokens = config.getMaxTokens() == null ? 1024 : config.getMaxTokens();
        JdkClientHttpRequestFactory factory = new JdkClientHttpRequestFactory(HTTP_CLIENT);
        factory.setReadTimeout(Duration.ofSeconds(timeout));
        RestClient.Builder builder = RestClient.builder().requestFactory(factory)
                .defaultHeader("Content-Type", MediaType.APPLICATION_JSON_VALUE);
        // Each protocol receives only its own authentication headers.
        if (format == AIFormatEnum.ANTHROPIC) {
            builder.defaultHeader("x-api-key", apiKey).defaultHeader("anthropic-version", "2023-06-01");
        } else {
            builder.defaultHeader("Authorization", "Bearer " + apiKey);
        }
        client = builder.build();
    }

    public static void validate(UserAIConfigDO config) {
        if (config == null || StringUtils.isBlank(config.getApiKey())) {
            throw new ApplicationException("请填写 API Key");
        }
        // Reject header control characters before constructing an authenticated request.
        if (config.getApiKey().length() > 255 || config.getApiKey().chars().anyMatch(Character::isISOControl)) {
            throw new ApplicationException("API Key 格式无效");
        }
        if (StringUtils.isBlank(config.getModelName()) || config.getModelName().length() > 128) {
            throw new ApplicationException("请填写不超过128字符的模型名称");
        }
        if (config.getTimeout() != null && (config.getTimeout() < 1 || config.getTimeout() > 120)) {
            throw new ApplicationException("超时时间须为1至120秒");
        }
        if (config.getMaxTokens() != null && (config.getMaxTokens() < 1 || config.getMaxTokens() > 128000)) {
            throw new ApplicationException("最大输出 Token 须为1至128000");
        }
        resolveEndpoint(config);
    }

    private static URI resolveEndpoint(UserAIConfigDO config) {
        AIFormatEnum format = AIFormatEnum.resolve(config.getApiFormat());
        String baseUrl = StringUtils.trimToEmpty(config.getBaseUrl());
        // Preserve provider defaults for legacy clients while allowing any explicit endpoint.
        if (baseUrl.isEmpty()) {
            AIProviderEnum provider = AIProviderEnum.getByCode(config.getProvider());
            baseUrl = provider == null ? "" : StringUtils.defaultString(provider.getDefaultBaseUrl());
        }
        String path = StringUtils.defaultIfBlank(config.getCompletionsPath(), format.getDefaultPath()).trim();
        try {
            URI base = URI.create(baseUrl);
            URI relative = URI.create(path);
            // Keep credentials out of URLs and prevent the path from selecting another host.
            if (!("https".equalsIgnoreCase(base.getScheme()) || "http".equalsIgnoreCase(base.getScheme()))
                    || base.getHost() == null || base.getUserInfo() != null || base.getQuery() != null
                    || base.getFragment() != null || !path.startsWith("/") || path.startsWith("//")
                    || relative.isAbsolute() || relative.getQuery() != null || relative.getFragment() != null
                    || baseUrl.length() > 1024 || path.length() > 255) {
                throw new IllegalArgumentException();
            }
            return URI.create(baseUrl.replaceAll("/+$", "") + path);
        } catch (IllegalArgumentException e) {
            throw new ApplicationException("请填写有效的 HTTP(S) 基础地址和以 / 开头的接口路径，地址中不能包含凭证、查询参数或片段");
        }
    }

    public boolean matches(UserAIConfigDO config) {
        // Recheck the persisted settings so a concurrent save cannot leave a stale cached client active.
        return format == AIFormatEnum.resolve(config.getApiFormat())
                && endpoint.equals(resolveEndpoint(config))
                && model.equals(StringUtils.trim(config.getModelName()))
                && Objects.equals(apiKey, StringUtils.trim(config.getApiKey()))
                && timeout == (config.getTimeout() == null ? 60 : config.getTimeout())
                && maxTokens == (config.getMaxTokens() == null ? 1024 : config.getMaxTokens());
    }

    @Override
    public ChatResponse call(Prompt prompt) {
        Map<String, Object> body = buildBody(prompt);
        String response;
        try {
            // A single request avoids duplicate paid calls after timeouts or upstream errors.
            response = client.post().uri(endpoint).body(body).retrieve()
                    .onStatus(status -> !status.is2xxSuccessful(), (request, result) -> {
                        throw new ApplicationException("AI 服务请求失败（HTTP " + result.getStatusCode().value()
                                + "），请检查接口地址、密钥、模型权限及额度");
                    }).body(String.class);
        } catch (ResourceAccessException e) {
            throw new ApplicationException("AI 服务连接失败或超时，请检查地址和超时时间；本次请求未自动重试");
        } catch (RestClientException e) {
            // Upstream bodies and exception causes can contain secrets or conversation content.
            throw new ApplicationException("AI 服务响应无法读取，请检查接口格式");
        }
        return new ChatResponse(List.of(new Generation(parseText(response))));
    }

    private Map<String, Object> buildBody(Prompt prompt) {
        List<Map<String, String>> messages = new ArrayList<>();
        List<String> system = new ArrayList<>();
        for (Message message : prompt.getInstructions()) {
            MessageType type = message.getMessageType();
            // The application's chat workflows exchange text, not tool calls or image blocks.
            if (type != MessageType.SYSTEM && type != MessageType.USER && type != MessageType.ASSISTANT) {
                throw new ApplicationException("当前 AI 配置仅支持文字对话");
            }
            String content = StringUtils.defaultString(message.getContent());
            if (format == AIFormatEnum.ANTHROPIC && type == MessageType.SYSTEM) {
                system.add(content);
            } else {
                messages.add(Map.of("role", type.getValue(), "content", content));
            }
        }
        if (messages.isEmpty()) {
            throw new ApplicationException("AI 请求缺少对话内容");
        }
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("model", model);
        body.put("messages", messages);
        body.put("stream", false);
        if (format == AIFormatEnum.ANTHROPIC) {
            // Anthropic requires an output limit and a separate top-level system prompt.
            body.put("max_tokens", maxTokens);
            if (!system.isEmpty()) {
                body.put("system", String.join("\n\n", system));
            }
        }
        return body;
    }

    private String parseText(String response) {
        if (StringUtils.isBlank(response)) {
            throw new ApplicationException("AI 返回内容为空");
        }
        try {
            JsonNode root = JSON.readTree(response);
            JsonNode content = format == AIFormatEnum.ANTHROPIC ? root.path("content")
                    : root.path("choices").path(0).path("message").path("content");
            StringBuilder text = new StringBuilder();
            if (format == AIFormatEnum.OPENAI && content.isTextual()) {
                text.append(content.textValue());
            } else if (content.isArray()) {
                // Ignore thinking/tool blocks; only display actual assistant text.
                for (JsonNode block : content) {
                    if ("text".equals(block.path("type").asText()) && block.path("text").isTextual()) {
                        text.append(block.path("text").textValue());
                    }
                }
            }
            if (text.toString().isBlank()) {
                throw new ApplicationException("AI 未返回文字内容，请检查接口格式和模型是否支持文字对话");
            }
            return text.toString();
        } catch (JsonProcessingException e) {
            throw new ApplicationException("AI 响应不是有效的 JSON，请检查接口地址和格式");
        }
    }
}
