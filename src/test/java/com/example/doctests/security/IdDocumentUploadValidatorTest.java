package com.example.doctests.security;

import com.example.doctests.support.IdDocumentUploadValidator;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

class IdDocumentUploadValidatorTest {
    @TempDir
    Path tempDir;

    @Test
    void acceptsSupportedFormatsWithMatchingFileSignatures() throws IOException {
        Path pdf = Files.writeString(tempDir.resolve("id.pdf"), "%PDF-1.7",
                StandardCharsets.US_ASCII);
        Path png = Files.write(tempDir.resolve("id.png"),
                new byte[] {(byte) 0x89, 'P', 'N', 'G', 0x0D, 0x0A, 0x1A, 0x0A});
        Path jpeg = Files.write(tempDir.resolve("id.jpeg"),
                new byte[] {(byte) 0xFF, (byte) 0xD8, (byte) 0xFF});

        assertEquals(pdf.toAbsolutePath().normalize(), IdDocumentUploadValidator.validate(pdf));
        assertEquals(png.toAbsolutePath().normalize(), IdDocumentUploadValidator.validate(png));
        assertEquals(jpeg.toAbsolutePath().normalize(), IdDocumentUploadValidator.validate(jpeg));
    }

    @Test
    void rejectsUnsupportedOrMismatchedFileSignatures() throws IOException {
        Path unsupported = Files.writeString(tempDir.resolve("id.txt"), "not an ID document");
        Path mismatched = Files.writeString(tempDir.resolve("id.pdf"), "plain text");

        assertThrows(IllegalArgumentException.class,
                () -> IdDocumentUploadValidator.validate(unsupported));
        assertThrows(IllegalArgumentException.class,
                () -> IdDocumentUploadValidator.validate(mismatched));
    }

    @Test
    void rejectsEmptyAndOversizedFiles() throws IOException {
        Path empty = Files.createFile(tempDir.resolve("empty.pdf"));
        Path oversized = tempDir.resolve("oversized.pdf");
        try (var output = Files.newOutputStream(oversized)) {
            output.write("%PDF-".getBytes(StandardCharsets.US_ASCII));
            output.write(new byte[(int) IdDocumentUploadValidator.MAX_FILE_SIZE_BYTES]);
        }

        assertThrows(IllegalArgumentException.class,
                () -> IdDocumentUploadValidator.validate(empty));
        assertThrows(IllegalArgumentException.class,
                () -> IdDocumentUploadValidator.validate(oversized));
    }

    @Test
    void rejectsMissingFilesWithoutIncludingTheirPathInTheError() {
        Path missing = tempDir.resolve("private-id.pdf");

        IllegalArgumentException exception = assertThrows(IllegalArgumentException.class,
                () -> IdDocumentUploadValidator.validate(missing));

        org.junit.jupiter.api.Assertions.assertFalse(exception.getMessage().contains("private-id"));
    }
}
