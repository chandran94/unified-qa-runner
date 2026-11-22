package com.example.unifiedqarunner;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.zip.ZipEntry;
import java.util.zip.ZipOutputStream;
import org.junit.jupiter.api.Test;

class ZipImportResourceLimitsTest {

    @Test
    void validImportAndBoundarySizesSucceed() throws Exception {
        Path root = Files.createTempDirectory("qa-zip-limits-");
        try {
            RemoteImportConfig.ZipImportLimits limits = new RemoteImportConfig.ZipImportLimits(4096, 2, 5, 8, 2);
            RemoteImportConfig config = new RemoteImportConfig(root, limits);
            Path zip = writeZip(root, List.of(entry("a.txt", "12345"), entry("dir/b.txt", "123")));

            RemoteImportConfig.ImportEntry imported = config.extractZipFile(zip, "valid.zip");

            assertTrue(Files.isRegularFile(Path.of(imported.getPath(), "a.txt")));
            assertEquals("123", Files.readString(Path.of(imported.getPath(), "dir/b.txt")));
        } finally {
            deleteTree(root);
        }
    }

    @Test
    void entryCountAndEntryBytesAreBoundedAndCleaned() throws Exception {
        Path root = Files.createTempDirectory("qa-zip-limits-");
        try {
            RemoteImportConfig config = new RemoteImportConfig(root,
                    new RemoteImportConfig.ZipImportLimits(4096, 1, 4, 8, 4));

            Path tooMany = writeZip(root, List.of(entry("a.txt", "1"), entry("b.txt", "2")));
            assertThrows(IOException.class, () -> config.extractZipFile(tooMany, "too-many.zip"));
            assertNoExtractedDirectories(root);

            Path tooLarge = writeZip(root, List.of(entry("large.txt", "12345")));
            assertThrows(IOException.class, () -> config.extractZipFile(tooLarge, "too-large.zip"));
            assertNoExtractedDirectories(root);
        } finally {
            deleteTree(root);
        }
    }

    @Test
    void totalBytesAndArchiveInputAreBounded() throws Exception {
        Path root = Files.createTempDirectory("qa-zip-limits-");
        try {
            RemoteImportConfig config = new RemoteImportConfig(root,
                    new RemoteImportConfig.ZipImportLimits(32, 4, 8, 5, 4));
            Path tooMuchOutput = writeZip(root, List.of(entry("a.txt", "123"), entry("b.txt", "456")));
            assertThrows(IOException.class, () -> config.extractZipFile(tooMuchOutput, "total.zip"));
            assertNoExtractedDirectories(root);

            Path input = root.resolve("oversized.zip");
            Files.write(input, new byte[33]);
            assertThrows(IOException.class, () -> config.extractZipFile(input, "oversized.zip"));
            assertNoExtractedDirectories(root);
        } finally {
            deleteTree(root);
        }
    }

    @Test
    void actualArchiveCompressionRatioIsBounded() throws Exception {
        Path root = Files.createTempDirectory("qa-zip-ratio-");
        try {
            RemoteImportConfig config = new RemoteImportConfig(root,
                    new RemoteImportConfig.ZipImportLimits(4096, 4, 4096, 4096, 4, 10.0d));
            byte[] highlyCompressible = new byte[2048];
            Path compressed = writeZip(root, List.of(new Entry("zeros.bin", highlyCompressible)));

            assertThrows(IOException.class, () -> config.extractZipFile(compressed, "ratio.zip"));
            assertNoExtractedDirectories(root);
        } finally {
            deleteTree(root);
        }
    }

    @Test
    void multipleHighlyCompressibleEntriesRemainBoundedByArchiveRatio() throws Exception {
        Path root = Files.createTempDirectory("qa-zip-ratio-multi-");
        try {
            RemoteImportConfig config = new RemoteImportConfig(root,
                    new RemoteImportConfig.ZipImportLimits(8192, 4, 4096, 8192, 4, 5.0d));
            byte[] repeated = new byte[1024];
            Path compressed = writeZip(root, List.of(
                    new Entry("one.bin", repeated), new Entry("two.bin", repeated)));

            assertThrows(IOException.class, () -> config.extractZipFile(compressed, "multi-ratio.zip"));
            assertNoExtractedDirectories(root);
        } finally {
            deleteTree(root);
        }
    }

    @Test
    void dataDescriptorEntryIsCountedFromActualOutputAndEmptyArchiveIsRejected() throws Exception {
        Path root = Files.createTempDirectory("qa-zip-ratio-descriptor-");
        try {
            RemoteImportConfig config = new RemoteImportConfig(root,
                    new RemoteImportConfig.ZipImportLimits(8192, 4, 4096, 8192, 4, 5.0d));
            byte[] repeated = new byte[1024];
            Path descriptorArchive = writeZip(root, List.of(new Entry("descriptor.bin", repeated)));

            // ZipOutputStream emits a data descriptor for this deflated entry; metadata is not
            // used as the decompressed-byte accounting source.
            assertThrows(IOException.class, () -> config.extractZipFile(descriptorArchive, "descriptor-ratio.zip"));
            assertNoExtractedDirectories(root);

            Path emptyArchive = writeZip(root, List.of());
            assertThrows(IOException.class, () -> config.extractZipFile(emptyArchive, "empty.zip"));
            assertNoExtractedDirectories(root);
        } finally {
            deleteTree(root);
        }
    }

    @Test
    void pathDepthZipSlipAndDuplicatePathsAreRejected() throws Exception {
        Path root = Files.createTempDirectory("qa-zip-limits-");
        try {
            RemoteImportConfig config = new RemoteImportConfig(root,
                    new RemoteImportConfig.ZipImportLimits(4096, 8, 64, 128, 2));
            assertThrows(IOException.class, () -> config.extractZipFile(
                    writeZip(root, List.of(entry("a/b/c.txt", "x"))), "deep.zip"));
            assertThrows(IOException.class, () -> config.extractZipFile(
                    writeZip(root, List.of(entry("../outside.txt", "x"))), "slip.zip"));
            assertThrows(IOException.class, () -> config.extractZipFile(
                    writeZip(root, List.of(entry("same.txt", "a"), entry("same.txt", "b"))), "duplicate.zip"));
            assertFalse(Files.exists(root.resolve("outside.txt")));
            assertNoExtractedDirectories(root);
        } finally {
            deleteTree(root);
        }
    }

    @Test
    void nestedArchivesAreNotRecursivelyExtractedAndMalformedInputCleansUp() throws Exception {
        Path root = Files.createTempDirectory("qa-zip-limits-");
        try {
            RemoteImportConfig config = new RemoteImportConfig(root,
                    new RemoteImportConfig.ZipImportLimits(4096, 8, 4096, 8192, 4));
            byte[] nested = zipBytes(List.of(entry("nested.txt", "not recursively extracted")));
            RemoteImportConfig.ImportEntry imported = config.extractZipFile(
                    writeZip(root, List.of(new Entry("nested.zip", nested))), "nested.zip");
            assertTrue(Files.isRegularFile(Path.of(imported.getPath(), "nested.zip")));
            assertFalse(Files.exists(Path.of(imported.getPath(), "nested.txt")));

            Path malformed = root.resolve("malformed.zip");
            Files.writeString(malformed, "not a zip", StandardCharsets.UTF_8);
            assertThrows(IOException.class, () -> config.extractZipFile(malformed, "malformed.zip"));
            assertEquals(1, config.getImports().size());
        } finally {
            deleteTree(root);
        }
    }

    private static Path writeZip(Path root, List<Entry> entries) throws IOException {
        Path zip = Files.createTempFile(root, "fixture-", ".zip");
        Files.write(zip, zipBytes(entries));
        return zip;
    }

    private static byte[] zipBytes(List<Entry> entries) throws IOException {
        ByteArrayOutputStream bytes = new ByteArrayOutputStream();
        try (ZipOutputStream output = new ZipOutputStream(bytes)) {
            for (Entry item : entries) {
                output.putNextEntry(new ZipEntry(item.name()));
                output.write(item.content());
                output.closeEntry();
            }
        }
        return bytes.toByteArray();
    }

    private static Entry entry(String name, String content) {
        return new Entry(name, content.getBytes(StandardCharsets.UTF_8));
    }

    private static void assertNoExtractedDirectories(Path root) throws IOException {
        try (var paths = Files.list(root.resolve("imports"))) {
            assertTrue(paths.noneMatch(path -> Files.isDirectory(path)), "partial extraction remained");
        }
    }

    private static void deleteTree(Path root) throws IOException {
        if (root == null || !Files.exists(root)) return;
        try (var paths = Files.walk(root)) {
            paths.sorted(java.util.Comparator.reverseOrder()).forEach(path -> {
                try { Files.deleteIfExists(path); } catch (IOException e) { throw new RuntimeException(e); }
            });
        }
    }

    private record Entry(String name, byte[] content) {}
}
