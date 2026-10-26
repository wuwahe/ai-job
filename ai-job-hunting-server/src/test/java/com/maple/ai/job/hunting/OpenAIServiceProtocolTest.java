package com.maple.ai.job.hunting;

import com.maple.ai.job.hunting.frame.exp.AIPowerException;
import com.maple.ai.job.hunting.service.ai.impl.OpenAIService;
import org.junit.jupiter.api.Test;
import org.springframework.ai.chat.ChatClient;
import org.springframework.ai.chat.ChatResponse;
import org.springframework.ai.chat.Generation;
import org.springframework.ai.chat.messages.Message;

import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;

import static org.junit.jupiter.api.Assertions.*;

class OpenAIServiceProtocolTest {
    @Test
    void sharedChatFlowIncludesLatestQuestionAndPreservesHistory() {
        AtomicReference<List<Message>> sent = new AtomicReference<>();
        TestService service = new TestService(prompt -> {
            sent.set(List.copyOf(prompt.getInstructions()));
            return new ChatResponse(List.of(new Generation("answer")));
        });
        service.sessionChat("test-session", "first question");
        assertEquals(List.of("instructions", "first question"),
                sent.get().stream().map(Message::getContent).toList());
        service.sessionChat("test-session", "second question");
        assertEquals(List.of("instructions", "first question", "answer", "second question"),
                sent.get().stream().map(Message::getContent).toList());
        assertEquals(5, service.getMsgSessionMap().get("test-session").size());
    }

    @Test
    void failedCallRemovesUnansweredTurnBeforeRetry() {
        AtomicInteger attempts = new AtomicInteger();
        AtomicReference<List<Message>> sent = new AtomicReference<>();
        TestService service = new TestService(prompt -> {
            if (attempts.getAndIncrement() == 0) {
                throw new IllegalStateException("local test failure");
            }
            sent.set(List.copyOf(prompt.getInstructions()));
            return new ChatResponse(List.of(new Generation("answer")));
        });
        assertThrows(AIPowerException.class, () -> service.sessionChat("test-session", "failed question"));
        service.sessionChat("test-session", "new question");
        assertEquals(List.of("instructions", "new question"),
                sent.get().stream().map(Message::getContent).toList());
    }

    private static class TestService extends OpenAIService {
        private final ChatClient client;
        private final Map<String, List<Message>> messages = new HashMap<>();

        TestService(ChatClient client) {
            this.client = client;
        }

        @Override
        protected ChatClient getClient() {
            return client;
        }

        @Override
        public Map<String, List<Message>> getMsgSessionMap() {
            return messages;
        }

        @Override
        public String buildSystemFirstPrompt() {
            return "instructions";
        }

        @Override
        protected void persistentMessage(String sessionId, List<Message> messageList) {
            // Conversation construction is tested without database access.
        }
    }
}
