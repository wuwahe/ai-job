package com.maple.ai.job.hunting.common.document;

import com.maple.ai.job.hunting.frame.exp.ApplicationException;
import com.maple.ai.job.hunting.utils.FileTypeDetector;
import org.apache.pdfbox.Loader;
import org.apache.pdfbox.pdmodel.PDDocument;
import org.apache.pdfbox.pdmodel.encryption.InvalidPasswordException;
import org.apache.pdfbox.text.PDFTextStripper;
import org.apache.poi.EncryptedDocumentException;
import org.apache.poi.hwpf.extractor.WordExtractor;
import org.apache.poi.xwpf.extractor.XWPFWordExtractor;
import org.apache.poi.xwpf.usermodel.XWPFDocument;
import org.springframework.stereotype.Component;

import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.Writer;
import java.util.zip.ZipInputStream;

/** Extracts text without uploading documents or requiring provider credentials. */
@Component
public class ResumeTextExtractor {
    public static final int MAX_FILE_BYTES = 20 * 1024 * 1024;
    public static final int MAX_TEXT_CHARS = 100_000;
    public static final int MAX_PDF_PAGES = 100;
    private static final int MAX_ZIP_ENTRIES = 1_000;
    private static final int MAX_EXPANDED_BYTES = 40 * 1024 * 1024;

    public record ExtractedResume(String text, String extension) { }

    public ExtractedResume extract(InputStream input) throws IOException {
        // Bound reads independently of the HTTP multipart limit, including service-level callers.
        byte[] bytes = input.readNBytes(MAX_FILE_BYTES + 1);
        if (bytes.length == 0) {
            throw new ApplicationException("简历文件为空，请重新上传");
        }
        if (bytes.length > MAX_FILE_BYTES) {
            throw new ApplicationException("简历文件不能超过20MB");
        }
        // Browser uploads may be named 'blob' or carry an incorrect MIME type; inspect the bytes.
        String extension = FileTypeDetector.detectFileType(bytes);
        String text;
        try {
            text = switch (extension) {
                case "pdf" -> readPdf(bytes);
                case "docx" -> readDocx(bytes);
                case "doc" -> readDoc(bytes);
                default -> throw new ApplicationException("仅支持PDF、DOCX、DOC简历；图片请先进行OCR并导出含文字的文件");
            };
        } catch (InvalidPasswordException | EncryptedDocumentException e) {
            throw new ApplicationException("简历已加密，请解除密码保护后重新上传");
        } catch (ApplicationException e) {
            throw e;
        } catch (IOException | RuntimeException e) {
            // Parser diagnostics can contain file contents; expose only an actionable error.
            throw new ApplicationException("简历文件无法解析，请检查是否损坏、加密或格式不符，或重新导出为含文字的PDF/DOCX");
        }
        // Preserve paragraphs and Unicode text, but remove control bytes PostgreSQL cannot store.
        text = text.replace("\r\n", "\n").replace('\r', '\n')
                .replaceAll("[\\p{Cntrl}&&[^\\n\\t]]", "").strip();
        if (text.isBlank()) {
            throw new ApplicationException("未提取到简历文字；扫描件或图片PDF请先进行OCR，或上传可复制文字的PDF/DOCX");
        }
        if (text.length() > MAX_TEXT_CHARS) {
            throw new ApplicationException("简历文字不能超过100000字符，请精简后上传");
        }
        return new ExtractedResume(text, extension);
    }

    private String readPdf(byte[] bytes) throws IOException {
        try (PDDocument document = Loader.loadPDF(bytes)) {
            if (document.isEncrypted()) {
                throw new ApplicationException("简历已加密，请解除密码保护后重新上传");
            }
            if (document.getNumberOfPages() > MAX_PDF_PAGES) {
                throw new ApplicationException("PDF简历不能超过100页");
            }
            PDFTextStripper stripper = new PDFTextStripper();
            stripper.setSortByPosition(true);
            // Stop accumulating excessive output instead of silently truncating resume facts.
            BoundedTextWriter output = new BoundedTextWriter();
            stripper.writeText(document, output);
            return output.toString();
        }
    }

    private String readDocx(byte[] bytes) throws IOException {
        checkZipSize(bytes);
        // POI validates the OOXML structure; a generic ZIP/XLSX is not accepted as a Word document.
        try (XWPFDocument document = new XWPFDocument(new ByteArrayInputStream(bytes));
             XWPFWordExtractor extractor = new XWPFWordExtractor(document)) {
            return extractor.getText();
        }
    }

    private String readDoc(byte[] bytes) throws IOException {
        // Closing the extractor also closes its underlying OLE2 document.
        try (WordExtractor extractor = new WordExtractor(new ByteArrayInputStream(bytes))) {
            return extractor.getText();
        }
    }

    private void checkZipSize(byte[] bytes) throws IOException {
        // Bound total expanded bytes as well as entry count before building the Word object model.
        try (ZipInputStream zip = new ZipInputStream(new ByteArrayInputStream(bytes))) {
            byte[] buffer = new byte[8192];
            long total = 0;
            int entries = 0;
            while (zip.getNextEntry() != null) {
                if (++entries > MAX_ZIP_ENTRIES) {
                    throw new ApplicationException("DOCX内部文件数量过多，请重新导出简历");
                }
                int count;
                while ((count = zip.read(buffer)) != -1) {
                    total += count;
                    if (total > MAX_EXPANDED_BYTES) {
                        throw new ApplicationException("DOCX解压内容过大，请精简文件后上传");
                    }
                }
            }
        }
    }

    private static final class BoundedTextWriter extends Writer {
        private final StringBuilder text = new StringBuilder();

        @Override
        public void write(char[] chars, int offset, int length) {
            if ((long) text.length() + length > MAX_TEXT_CHARS) {
                throw new ApplicationException("简历文字不能超过100000字符，请精简后上传");
            }
            text.append(chars, offset, length);
        }

        @Override
        public void flush() { }

        @Override
        public void close() { }

        @Override
        public String toString() {
            return text.toString();
        }
    }
}
