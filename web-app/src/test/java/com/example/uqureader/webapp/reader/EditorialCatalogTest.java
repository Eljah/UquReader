package com.example.uqureader.webapp.reader;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Files;
import java.nio.file.Path;
import java.security.MessageDigest;
import java.util.HexFormat;

import static org.junit.jupiter.api.Assertions.*;

class EditorialCatalogTest {
    @TempDir Path directory;

    @Test
    void loadsVerifiedEditionWithExplicitLanguageAndPreservesOldWorks() throws Exception {
        ReaderWorkCatalog catalog = ReaderWorkCatalog.loadDefault();
        int count = catalog.listWorks().size();
        Path data = directory.resolve("edition_test_r1.ttmorph.jsonl");
        Files.writeString(data, "{\"surface\":\"test\",\"analysis\":\"Unknown\",\"pageIndex\":0}\n");
        String hash = HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(Files.readAllBytes(data)));
        Path metadata = directory.resolve("edition_test_r1.metadata.json");
        Files.writeString(metadata, "{\"id\":\"edition_test_r1\",\"title\":\"Test\",\"language\":\"mhr\",\"sha256\":\"" + hash + "\"}");
        catalog.loadPublished(directory);
        assertEquals(count + 1, catalog.listWorks().size());
        assertEquals("mhr", catalog.find("edition_test_r1").orElseThrow().language);
        catalog.loadPublished(directory);
        assertEquals(count + 1, catalog.listWorks().size());
    }

    @Test
    void rejectsIncompleteOrModifiedPublication() throws Exception {
        ReaderWorkCatalog catalog = ReaderWorkCatalog.loadDefault();
        Files.writeString(directory.resolve("edition_bad.ttmorph.jsonl"), "{}\n");
        Files.writeString(directory.resolve("edition_bad.metadata.json"),
                "{\"id\":\"edition_bad\",\"language\":\"mhr\",\"sha256\":\"bad\"}");
        assertThrows(java.io.IOException.class, () -> catalog.loadPublished(directory));
        assertTrue(catalog.find("edition_bad").isEmpty());
    }
}
