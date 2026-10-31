package com.example.doctests.support;

import java.io.IOException;
import java.io.InputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Locale;

public final class IdDocumentUploadValidator {
    public static final long MAX_FILE_SIZE_BYTES = 10 * 1024 * 1024;

    private IdDocumentUploadValidator() {
    }

    public static Path validate(Path file) throws IOException {
        if (file == null || !Files.isRegularFile(file) || !Files.isReadable(file)) {
            throw new IllegalArgumentException("ID document must be a readable regular file");
        }

        long size = Files.size(file);
        if (size == 0 || size > MAX_FILE_SIZE_BYTES) {
            throw new IllegalArgumentException("ID document size is not allowed");
        }

        String extension = extension(file);
        try (InputStream input = Files.newInputStream(file)) {
            byte[] header = input.readNBytes(8);
            if (!matchesFormat(extension, header)) {
                throw new IllegalArgumentException("ID document format is not allowed");
            }
        }
        return file.toAbsolutePath().normalize();
    }

    private static String extension(Path file) {
        String name = file.getFileName().toString();
        int dot = name.lastIndexOf('.');
        return dot < 0 ? "" : name.substring(dot).toLowerCase(Locale.ROOT);
    }

    private static boolean matchesFormat(String extension, byte[] header) {
        return switch (extension) {
            case ".pdf" -> header.length >= 5
                    && header[0] == '%' && header[1] == 'P' && header[2] == 'D'
                    && header[3] == 'F' && header[4] == '-';
            case ".png" -> header.length == 8
                    && header[0] == (byte) 0x89 && header[1] == 'P' && header[2] == 'N'
                    && header[3] == 'G' && header[4] == 0x0D && header[5] == 0x0A
                    && header[6] == 0x1A && header[7] == 0x0A;
            case ".jpg", ".jpeg" -> header.length >= 3
                    && header[0] == (byte) 0xFF && header[1] == (byte) 0xD8
                    && header[2] == (byte) 0xFF;
            default -> false;
        };
    }
}
