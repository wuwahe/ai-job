package com.maple.ai.job.hunting;

import com.maple.ai.job.hunting.common.document.ResumeTextExtractor;
import com.maple.ai.job.hunting.frame.exp.ApplicationException;
import org.junit.jupiter.api.Test;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.nio.charset.StandardCharsets;
import java.util.zip.ZipEntry;
import java.util.zip.ZipOutputStream;

import static org.junit.jupiter.api.Assertions.*;

class ResumeTextExtractorTest {
    private final ResumeTextExtractor extractor = new ResumeTextExtractor();

    @Test
    void extractsPdfAcrossPagesWithoutFilenameOrMimeType() throws Exception {
        var resume = extract(ResumeDocuments.pdf("Backend engineer mail:test@example.com", 2, false));
        assertEquals("pdf", resume.extension());
        assertEquals(2, resume.text().split("Backend engineer", -1).length - 1);
        assertTrue(resume.text().contains("test@example.com"));
    }

    @Test
    void extractsChineseDocxParagraphsAndTables() throws Exception {
        var resume = extract(ResumeDocuments.docx("测试简历 邮箱:test@example.com 电话:13800138000"));
        assertEquals("docx", resume.extension());
        assertTrue(resume.text().contains("测试简历"));
        assertTrue(resume.text().contains("项目经历"));
        assertTrue(resume.text().contains("Java 后端开发"));
    }

    @Test
    void extractsLegacyWordDocument() throws Exception {
        var resume = extract(ResumeDocuments.doc("Backend engineer mail:test@example.com"));
        assertEquals("doc", resume.extension());
        assertTrue(resume.text().contains("Backend engineer"));
        assertTrue(resume.text().contains("test@example.com"));
    }

    @Test
    void rejectsEmptyAndOversizedUploadsBeforeParsing() {
        assertError(new byte[0], "为空");
        assertError(new byte[ResumeTextExtractor.MAX_FILE_BYTES + 1], "20MB");
    }

    @Test
    void rejectsImagesAndUnsupportedFiles() {
        assertError(new byte[]{(byte) 0xff, (byte) 0xd8, (byte) 0xff, 0}, "OCR");
        assertError("<html>not a resume</html>".getBytes(StandardCharsets.UTF_8), "仅支持");
    }

    @Test
    void rejectsDamagedPdfWithoutExposingItsContents() {
        var error = assertThrows(ApplicationException.class,
                () -> extract("%PDF-1.7 private-resume-marker".getBytes(StandardCharsets.UTF_8)));
        assertTrue(error.getMessage().contains("无法解析"));
        assertFalse(error.getMessage().contains("private-resume-marker"));
        assertNull(error.getCause());
    }

    @Test
    void rejectsScannedOrBlankPdfWithActionableMessage() throws Exception {
        assertError(ResumeDocuments.pdf("", 1, false), "OCR");
    }

    @Test
    void rejectsPasswordProtectedPdf() throws Exception {
        assertError(ResumeDocuments.pdf("Private resume", 1, true), "加密");
    }

    @Test
    void rejectsTooManyPdfPages() throws Exception {
        assertError(ResumeDocuments.pdf("", ResumeTextExtractor.MAX_PDF_PAGES + 1, false), "100页");
    }

    @Test
    void rejectsExcessivePdfTextWithoutTruncatingIt() throws Exception {
        assertError(ResumeDocuments.pdf("A".repeat(2000), 51, false), "100000字符");
    }

    @Test
    void rejectsGenericZipInsteadOfTrustingItsSignature() throws Exception {
        assertError(zip(1, 10), "无法解析");
    }

    @Test
    void rejectsArchiveExpansionAndExcessiveEntryCounts() throws Exception {
        assertError(zip(1, 41 * 1024 * 1024), "解压内容过大");
        assertError(zip(1001, 1), "内部文件数量过多");
    }

    private ResumeTextExtractor.ExtractedResume extract(byte[] bytes) throws Exception {
        return extractor.extract(new ByteArrayInputStream(bytes));
    }

    private void assertError(byte[] bytes, String message) {
        assertTrue(assertThrows(ApplicationException.class, () -> extract(bytes)).getMessage().contains(message));
    }

    private byte[] zip(int entries, int entrySize) throws Exception {
        try (ByteArrayOutputStream bytes = new ByteArrayOutputStream(); ZipOutputStream zip = new ZipOutputStream(bytes)) {
            byte[] chunk = new byte[8192];
            for (int i = 0; i < entries; i++) {
                zip.putNextEntry(new ZipEntry("entry-" + i));
                for (int remaining = entrySize; remaining > 0; remaining -= chunk.length) {
                    zip.write(chunk, 0, Math.min(remaining, chunk.length));
                }
                zip.closeEntry();
            }
            zip.finish();
            return bytes.toByteArray();
        }
    }
}
