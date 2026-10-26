package com.maple.ai.job.hunting;

import org.apache.pdfbox.pdmodel.PDDocument;
import org.apache.pdfbox.pdmodel.PDPage;
import org.apache.pdfbox.pdmodel.PDPageContentStream;
import org.apache.pdfbox.pdmodel.encryption.AccessPermission;
import org.apache.pdfbox.pdmodel.encryption.StandardProtectionPolicy;
import org.apache.pdfbox.pdmodel.font.PDType1Font;
import org.apache.pdfbox.pdmodel.font.Standard14Fonts;
import org.apache.poi.hwpf.HWPFDocument;
import org.apache.poi.xwpf.usermodel.XWPFDocument;

import java.io.ByteArrayOutputStream;
import java.io.InputStream;

/** Synthetic documents contain no real resumes, credentials, or provider calls. */
final class ResumeDocuments {
    static byte[] pdf(String text, int pages, boolean encrypted) throws Exception {
        try (PDDocument document = new PDDocument(); ByteArrayOutputStream bytes = new ByteArrayOutputStream()) {
            for (int i = 0; i < pages; i++) {
                PDPage page = new PDPage();
                document.addPage(page);
                if (!text.isEmpty()) {
                    try (PDPageContentStream content = new PDPageContentStream(document, page)) {
                        content.beginText();
                        content.setFont(new PDType1Font(Standard14Fonts.FontName.HELVETICA), 12);
                        content.newLineAtOffset(50, 700);
                        content.showText(text);
                        content.endText();
                    }
                }
            }
            if (encrypted) {
                document.protect(new StandardProtectionPolicy("test-owner", "test-reader", new AccessPermission()));
            }
            document.save(bytes);
            return bytes.toByteArray();
        }
    }

    static byte[] docx(String text) throws Exception {
        try (XWPFDocument document = new XWPFDocument(); ByteArrayOutputStream bytes = new ByteArrayOutputStream()) {
            document.createParagraph().createRun().setText(text);
            var table = document.createTable(1, 2);
            table.getRow(0).getCell(0).setText("项目经历");
            table.getRow(0).getCell(1).setText("Java 后端开发");
            document.write(bytes);
            return bytes.toByteArray();
        }
    }

    static byte[] doc(String text) throws Exception {
        try (InputStream fixture = ResumeDocuments.class.getResourceAsStream("/documents/empty.doc");
             HWPFDocument document = new HWPFDocument(fixture);
             ByteArrayOutputStream bytes = new ByteArrayOutputStream()) {
            document.getRange().insertBefore(text);
            document.write(bytes);
            return bytes.toByteArray();
        }
    }
}
