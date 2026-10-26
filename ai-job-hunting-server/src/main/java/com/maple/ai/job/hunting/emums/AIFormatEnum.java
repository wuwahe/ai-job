package com.maple.ai.job.hunting.emums;

import com.maple.ai.job.hunting.frame.exp.ApplicationException;
import lombok.AllArgsConstructor;
import lombok.Getter;

/**
 * Wire protocols are independent of the provider and model name.
 */
@Getter
@AllArgsConstructor
public enum AIFormatEnum {
    OPENAI("/chat/completions"),
    ANTHROPIC("/messages");

    private final String defaultPath;

    public static AIFormatEnum resolve(String value) {
        // Existing configurations predate this field and use OpenAI-compatible requests.
        if (value == null || value.isBlank()) {
            return OPENAI;
        }
        for (AIFormatEnum format : values()) {
            if (format.name().equals(value)) {
                return format;
            }
        }
        throw new ApplicationException("接口格式仅支持 OPENAI 或 ANTHROPIC");
    }
}
