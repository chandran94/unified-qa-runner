package com.example.unifiedqarunner;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.LinkedHashMap;
import java.util.Map;
import org.junit.jupiter.api.Test;

class WikiManagerTest {
    @Test
    void spacesPagesAndVersionsPersistSafely() throws Exception {
        Path project = Files.createTempDirectory("qa-wiki-");
        Files.writeString(project.resolve("qa-runner.json"), "{}");
        WikiManager manager = new WikiManager(project);

        WikiManager.Space space = manager.createSpace("QA Knowledge", "qa", "Runbooks", "alice");
        assertThrows(IllegalArgumentException.class, () -> manager.createSpace("Duplicate", "QA", "", "alice"));

        Map<String, Object> values = new LinkedHashMap<>();
        values.put("spaceId", space.id());
        values.put("title", "Release checklist");
        values.put("content", "<p>Safe</p><script>alert(1)</script><img src=x onerror=alert(1)>");
        WikiManager.Page page = manager.createPage(values, "alice");
        assertEquals(1, manager.listPages(space.id(), "", false).size());
        assertTrue(!manager.getPage(page.id()).content().contains("script"));
        assertTrue(!manager.getPage(page.id()).content().contains("onerror"));

        Map<String, Object> update = new LinkedHashMap<>();
        update.put("title", "Release checklist v2");
        update.put("content", "<h2>Published</h2>");
        update.put("status", WikiManager.PUBLISHED);
        update.put("version", page.version());
        WikiManager.Page revised = manager.updatePage(page.id(), update, "bob");
        assertEquals(2, revised.version());
        assertThrows(IllegalStateException.class, () -> manager.updatePage(page.id(), update, "alice"));

        WikiManager reloaded = new WikiManager(project);
        assertEquals("Release checklist v2", reloaded.getPage(page.id()).title());
        assertEquals("bob", reloaded.getPage(page.id()).updatedBy());
    }

    @Test
    void preventsCircularPageParents() throws Exception {
        Path project = Files.createTempDirectory("qa-wiki-tree-");
        Files.writeString(project.resolve("qa-runner.json"), "{}");
        WikiManager manager = new WikiManager(project);
        WikiManager.Space space = manager.createSpace("Docs", "docs", "", "alice");

        Map<String, Object> first = new LinkedHashMap<>();
        first.put("spaceId", space.id());
        first.put("title", "Parent");
        WikiManager.Page parent = manager.createPage(first, "alice");
        Map<String, Object> childValues = new LinkedHashMap<>();
        childValues.put("spaceId", space.id());
        childValues.put("parentPageId", parent.id());
        childValues.put("title", "Child");
        WikiManager.Page child = manager.createPage(childValues, "alice");

        Map<String, Object> move = new LinkedHashMap<>();
        move.put("parentPageId", child.id());
        move.put("title", "Parent");
        move.put("version", parent.version());
        assertThrows(IllegalArgumentException.class, () -> manager.updatePage(parent.id(), move, "alice"));
    }
}
