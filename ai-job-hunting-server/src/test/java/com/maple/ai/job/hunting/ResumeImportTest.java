package com.maple.ai.job.hunting;

import com.maple.ai.job.hunting.common.document.ResumeTextExtractor;
import com.maple.ai.job.hunting.frame.exp.ApplicationException;
import com.maple.ai.job.hunting.mapper.UserInfoMapper;
import com.maple.ai.job.hunting.mapper.UserResumeMapper;
import com.maple.ai.job.hunting.model.bo.UserInfoDO;
import com.maple.ai.job.hunting.model.bo.UserResumeDO;
import com.maple.ai.job.hunting.service.biz.UserService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.test.util.ReflectionTestUtils;

import java.io.ByteArrayInputStream;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.*;

class ResumeImportTest {
    private final UserInfoMapper users = mock(UserInfoMapper.class);
    private final UserResumeMapper resumes = mock(UserResumeMapper.class);
    private final UserService service = new UserService();

    @BeforeEach
    void configureImportWithoutAnyAiServiceOrCredentials() {
        ReflectionTestUtils.setField(service, "resumeTextExtractor", new ResumeTextExtractor());
        ReflectionTestUtils.setField(service, "userInfoMapper", users);
        ReflectionTestUtils.setField(service, "userResumeMapper", resumes);
        when(users.insert(any(UserInfoDO.class))).thenAnswer(call -> {
            call.<UserInfoDO>getArgument(0).setId(42L);
            return 1;
        });
    }

    @Test
    void firstImportCreatesUserAndStoresOriginalTextWithoutAiConfiguration() throws Exception {
        byte[] bytes = ResumeDocuments.docx("测试简历 邮箱:test@example.com 电话:13800138000");
        var result = service.importResume(new ByteArrayInputStream(bytes), "synthetic-user", "resume-test");
        assertEquals("test@example.com", result.getEmail());
        assertEquals("13800138000", result.getPhone());
        ArgumentCaptor<UserResumeDO> saved = ArgumentCaptor.forClass(UserResumeDO.class);
        verify(resumes).insert(saved.capture());
        assertEquals(42L, saved.getValue().getUserId());
        assertEquals("resume-test", saved.getValue().getResumeId());
        assertTrue(saved.getValue().getOssFileName().endsWith(".docx"));
        assertTrue(saved.getValue().getResumeContent().contains("Java 后端开发"));
    }

    @Test
    void unreadableReplacementCannotDeleteOrOverwriteExistingResume() throws Exception {
        assertThrows(ApplicationException.class, () -> service.importResume(
                new ByteArrayInputStream(ResumeDocuments.pdf("", 1, false)), "existing-user", "new-resume"));
        verifyNoInteractions(users, resumes);
    }
}
