package com.example.unifiedqarunner;

import org.junit.jupiter.api.Test;

import java.nio.file.Files;
import java.nio.file.Path;

import static org.junit.jupiter.api.Assertions.*;

class UserStoryStoreTest {

    @Test
    void createsProjectsEpicsAndStoriesWithStableIdsAndPagination() throws Exception {
        Path root = Files.createTempDirectory("qa-user-story-store-");
        UserStoryStore store = new UserStoryStore(root);

        UserStoryStore.Project project = store.createProject("PAY", "Payments", "Checkout work", "qa-team");
        UserStoryStore.Epic epic = store.createEpic(project.id(), "PAY-EPIC", "Checkout", "", "To Do", "High");
        UserStoryStore.Story first = store.createStory(project.id(), epic.id(), "Add card payment", "", "customer", "pay by card", "order completes", "Payment succeeds", "High", "To Do", 5, "dev", "reporter");
        store.createStory(project.id(), null, "Add refund flow", "", "customer", "request a refund", "money returns", "Refund is audited", "Medium", "To Do", 3, "", "reporter");

        assertEquals("PAY-1", first.id());
        assertEquals(2, store.countStories(project.id(), "", false));
        assertEquals(1, store.listStories(project.id(), "refund", 0, 1, false).size());
        assertEquals(1, store.listStories(project.id(), "", 1, 1, false).size());
        assertEquals(1, store.listEpics(project.id(), false).size());
    }

    @Test
    void validatesUniqueKeysAndSupportsArchiveRestoreWithoutDeletingData() throws Exception {
        Path root = Files.createTempDirectory("qa-user-story-store-");
        UserStoryStore store = new UserStoryStore(root);
        UserStoryStore.Project project = store.createProject("QA", "Quality", "", "");
        assertThrows(IllegalArgumentException.class, () -> store.createProject("qa", "Duplicate", "", ""));
        UserStoryStore.Story story = store.createStory(project.id(), null, "Smoke test", "", "", "", "", "", "Medium", "To Do", 1, "", "");

        store.archiveStory(story.id(), true);
        assertEquals(0, store.countStories(project.id(), "", false));
        assertEquals(1, store.countStories(project.id(), "", true));
        store.archiveStory(story.id(), false);
        assertEquals(1, store.countStories(project.id(), "", false));

        String persisted = Files.readString(root.resolve("qa-runner.json"));
        assertTrue(persisted.contains("userStorySchemaVersion"));
        assertTrue(persisted.contains("PAY") || persisted.contains("QA"));
    }

    @Test
    void enforcesSprintLifecycleAssignmentsAndKanbanStatusPersistence() throws Exception {
        Path root = Files.createTempDirectory("qa-sprint-store-");
        UserStoryStore store = new UserStoryStore(root);
        UserStoryStore.Project project = store.createProject("WEB", "Web", "", "");
        UserStoryStore.Project other = store.createProject("API", "API", "", "");
        UserStoryStore.Story story = store.createStory(project.id(), null, "Board story", "", "", "", "", "", "Medium", "To Do", 3, "qa", "");
        UserStoryStore.Story otherStory = store.createStory(other.id(), null, "Other story", "", "", "", "", "", "Low", "To Do", 1, "", "");
        UserStoryStore.Sprint sprint = store.createSprint(project.id(), "Iteration 1", "Ship board", "2026-11-10", "2026-11-20");

        assertEquals("WEB-S1", sprint.id());
        assertThrows(IllegalArgumentException.class, () -> store.assignStoryToSprint(sprint.id(), otherStory.id()));
        assertEquals(sprint.id(), store.assignStoryToSprint(sprint.id(), story.id()).sprintId());
        assertEquals("Active", store.startSprint(sprint.id()).status());
        assertThrows(IllegalArgumentException.class, () -> store.startSprint(sprint.id()));
        assertEquals("In Progress", store.updateStory(story.id(), null, null, null, null, null, null, null, null, "In Progress", 3, null, null).status());
        assertEquals("Completed", store.completeSprint(sprint.id()).status());
        assertEquals(1, store.listSprints(project.id(), true).size());
        assertThrows(IllegalArgumentException.class, () -> store.assignStoryToSprint(sprint.id(), story.id()));
    }

    @Test
    void persistsManyToManyTestCaseLinksAndRejectsDuplicates() throws Exception {
        Path root = Files.createTempDirectory("qa-story-links-");
        UserStoryStore store = new UserStoryStore(root);
        UserStoryStore.Project project = store.createProject("QA", "Quality", "", "");
        UserStoryStore.Story first = store.createStory(project.id(), null, "First story", "", "", "", "", "", "Medium", "To Do", 1, "", "");
        UserStoryStore.Story second = store.createStory(project.id(), null, "Second story", "", "", "", "", "", "Medium", "To Do", 1, "", "");

        store.linkTestCase(first.id(), "tc-1");
        store.linkTestCase(second.id(), "tc-1");
        assertThrows(IllegalArgumentException.class, () -> store.linkTestCase(first.id(), "tc-1"));
        assertEquals(1, store.listTestCaseLinks(first.id()).size());
        assertTrue(Files.readString(root.resolve("qa-runner.json")).contains("userStoryTestCaseLinks"));
        store.unlinkTestCase(first.id(), "tc-1");
        assertTrue(store.listTestCaseLinks(first.id()).isEmpty());
        assertEquals(1, store.listTestCaseLinks(second.id()).size());
    }

    @Test
    void persistsBoardStatusTransitionsWithoutDuplicatingOrChangingStoryDetails() throws Exception {
        Path root = Files.createTempDirectory("qa-board-status-");
        UserStoryStore store = new UserStoryStore(root);
        UserStoryStore.Project project = store.createProject("QARS", "QA Runner", "", "");
        UserStoryStore.Story story = store.createStory(project.id(), null, "Drag me", "Keep this description",
                "a tester", "move a card", "status persists", "criteria", "High", "In Progress", 8, "qa", "reporter");

        for (String status : new String[]{"Testing", "Done", "In Progress", "Done"}) {
            store.updateStoryStatus(story.id(), status, project.id());
        }

        UserStoryStore.Story reloaded = new UserStoryStore(root).listStories(project.id(), "", 0, 10, false).get(0);
        assertEquals("Done", reloaded.status());
        assertEquals(story.id(), reloaded.id());
        assertEquals(story.title(), reloaded.title());
        assertEquals(story.description(), reloaded.description());
        assertEquals(story.storyPoints(), reloaded.storyPoints());
        assertEquals("reporter", new UserStoryStore(root).creatorFor(story.id()).displayName());
        assertEquals(1, new UserStoryStore(root).countStories(project.id(), "", false));
        assertThrows(IllegalArgumentException.class, () -> store.updateStoryStatus(story.id(), "Done", "other-project"));
        assertThrows(IllegalArgumentException.class, () -> store.updateStoryStatus(story.id(), "Unknown", project.id()));
    }

    @Test
    void persistsAttachmentsAndValidatesRelatedLinksWithoutChangingStoryData() throws Exception {
        Path root = Files.createTempDirectory("qa-story-assets-");
        UserStoryStore store = new UserStoryStore(root);
        UserStoryStore.Project project = store.createProject("LINK", "Links", "", "");
        UserStoryStore.Story story = store.createStory(project.id(), null, "Asset story", "description", "", "", "", "", "Medium", "To Do", 2, "qa", "creator");

        UserStoryStore.Attachment attachment = store.addAttachment(story.id(), "evidence.png", "generated.bin", 42, "image/png", "user-1", "User One");
        assertEquals(1, new UserStoryStore(root).listAttachments(story.id()).size());
        assertEquals(attachment.id(), new UserStoryStore(root).findAttachment(attachment.id()).id());

        UserStoryStore.RelatedLink link = store.addRelatedLink(story.id(), "Requirements", "https://example.com/requirements", "Requirement");
        assertEquals("Requirement", link.type());
        assertThrows(IllegalArgumentException.class, () -> store.addRelatedLink(story.id(), "Unsafe", "javascript:alert(1)", "Other"));
        assertThrows(IllegalArgumentException.class, () -> store.addRelatedLink(story.id(), "Duplicate", "https://example.com/requirements", "Other"));
        store.updateRelatedLink(link.id(), "Docs", "https://example.com/docs", "Documentation");
        assertEquals("Docs", new UserStoryStore(root).listRelatedLinks(story.id()).get(0).title());
        store.removeRelatedLink(link.id());
        assertTrue(store.listRelatedLinks(story.id()).isEmpty());

        UserStoryStore.Story created = store.createStory(project.id(), null, "Server creator", "", "", "", "", "", "Medium", "To Do", 1, "", "ignored-client-reporter", "user-42", "Authenticated User");
        assertEquals("user-42", store.creatorFor(created.id()).userId());
        store.updateStory(created.id(), null, "Renamed", null, null, null, null, null, null, null, 1, "new assignee", "attacker");
        assertEquals("Authenticated User", store.creatorFor(created.id()).displayName());
    }

    @Test
    void preservesWorkItemMetadataAndServerOwnedActivity() throws Exception {
        Path root = Files.createTempDirectory("qa-story-history-");
        UserStoryStore store = new UserStoryStore(root);
        UserStoryStore.Project project = store.createProject("HIST", "History", "", "");
        UserStoryStore.Story story = store.createStory(project.id(), null, "Audited story", "", "", "", "", "", "High", "To Do", 5, "qa", "legacy-reporter", "user-1", "Original creator");
        UserStoryStore.Story changed = store.updateStory(story.id(), null, null, null, null, null, null, null, "Critical", "In Progress", 8, "owner", "attacker", 90, "High", "user-2", "Changed by user");
        assertEquals(90, changed.businessValue());
        assertEquals("High", changed.risk());
        assertEquals("Original creator", store.creatorFor(story.id()).displayName());
        store.addComment(story.id(), "Ready for testing", "user-3", "Reviewer");
        assertTrue(store.listActivity(story.id()).stream().anyMatch(item -> item.action().equals("Comment")));
        assertTrue(store.listActivity(story.id()).stream().anyMatch(item -> item.action().equals("Changed")));
        assertTrue(Files.readString(root.resolve("qa-runner.json")).contains("userStoryActivity"));
    }

    @Test
    void commentsAndSprintMappingPersistAcrossReloadAndRejectInvalidInput() throws Exception {
        Path root = Files.createTempDirectory("qa-story-regressions-");
        UserStoryStore store = new UserStoryStore(root);
        UserStoryStore.Project project = store.createProject("REG", "Regression", "", "");
        UserStoryStore.Project otherProject = store.createProject("OTH", "Other", "", "");
        UserStoryStore.Story story = store.createStory(project.id(), null, "Persisted story", "keep", "a", "b", "c", "criteria", "High", "To Do", 8, "owner", "reporter");
        UserStoryStore.Sprint first = store.createSprint(project.id(), "First", "", "2026-11-10", "2026-11-20");
        UserStoryStore.Sprint second = store.createSprint(project.id(), "Second", "", "2026-11-21", "2026-11-30");
        UserStoryStore.Sprint other = store.createSprint(otherProject.id(), "Other", "", "2026-11-10", "2026-11-20");

        assertThrows(IllegalArgumentException.class, () -> store.addComment(story.id(), "   ", "u", "User"));
        UserStoryStore.Activity comment = store.addComment(story.id(), "Keep this comment", "u", "User");
        assertEquals("Comment", comment.action());
        assertEquals("User", comment.actorName());
        assertTrue(new UserStoryStore(root).listActivity(story.id()).stream().anyMatch(a -> a.id().equals(comment.id())));

        UserStoryStore.Story assigned = store.updateStory(story.id(), null, null, null, null, null, null, null, null, null, 8, null, null, 0, "Medium", "u", "User", first.id());
        assertEquals(first.id(), assigned.sprintId());
        UserStoryStore.Story moved = store.updateStory(story.id(), null, null, null, null, null, null, null, null, null, 8, null, null, 0, "Medium", "u", "User", second.id());
        assertEquals(second.id(), moved.sprintId());
        UserStoryStore.Story backlog = store.updateStory(story.id(), null, null, null, null, null, null, null, null, null, 8, null, null, 0, "Medium", "u", "User", "");
        assertTrue(backlog.sprintId().isBlank());
        assertThrows(IllegalArgumentException.class, () -> store.updateStory(story.id(), null, null, null, null, null, null, null, null, null, 8, null, null, 0, "Medium", "u", "User", other.id()));

        UserStoryStore.Story reloaded = new UserStoryStore(root).listStories(project.id(), "", 0, 10, false).get(0);
        assertTrue(reloaded.sprintId().isBlank());
        assertEquals(1, new UserStoryStore(root).listActivity(story.id()).stream().filter(a -> a.action().equals("Comment")).count());
    }

    @Test
    void editsAndSoftDeletesOnlyTheSelectedCommentWhilePreservingAuditMetadata() throws Exception {
        Path root = Files.createTempDirectory("qa-comment-actions-");
        UserStoryStore store = new UserStoryStore(root);
        UserStoryStore.Project project = store.createProject("COM", "Comments", "", "");
        UserStoryStore.Story story = store.createStory(project.id(), null, "Comment story", "", "", "", "", "", "Medium", "To Do", 1, "", "");
        UserStoryStore.Activity first = store.addComment(story.id(), "Original", "u1", "Author One");
        UserStoryStore.Activity second = store.addComment(story.id(), "Keep me", "u2", "Author Two");

        UserStoryStore.Activity edited = store.updateComment(story.id(), first.id(), "Updated", "editor", "Editor");
        assertEquals("Updated", edited.detail());
        assertTrue(edited.edited());
        assertEquals(first.createdAt(), edited.createdAt());
        assertEquals(first.actorName(), edited.actorName());
        assertThrows(IllegalArgumentException.class, () -> store.updateComment(story.id(), first.id(), "  ", "u", "User"));

        UserStoryStore.Activity deleted = store.deleteComment(story.id(), first.id(), "deleter", "Deleter");
        assertTrue(deleted.deleted());
        assertEquals("This comment was deleted", deleted.detail());
        assertEquals(first.createdAt(), deleted.createdAt());
        assertEquals("Keep me", store.listActivity(story.id()).stream().filter(a -> a.id().equals(second.id())).findFirst().orElseThrow().detail());
        assertThrows(IllegalArgumentException.class, () -> store.updateComment(story.id(), first.id(), "Again", "u", "User"));
        assertTrue(new UserStoryStore(root).listActivity(story.id()).stream().anyMatch(a -> a.id().equals(first.id()) && a.deleted()));
    }

    @Test
    void validatesInternalWorkItemLinksAndPreventsCycles() throws Exception {
        Path root = Files.createTempDirectory("qa-story-graph-");
        UserStoryStore store = new UserStoryStore(root);
        UserStoryStore.Project project = store.createProject("GRAPH", "Graph", "", "");
        UserStoryStore.Story parent = store.createStory(project.id(), null, "Parent story", "", "", "", "", "", "Medium", "To Do", 1, "", "");
        UserStoryStore.Story child = store.createStory(project.id(), null, "Child story", "", "", "", "", "", "Medium", "To Do", 1, "", "");
        store.addRelatedLink(parent.id(), "Child story", child.id(), "Child");
        assertThrows(IllegalArgumentException.class, () -> store.addRelatedLink(child.id(), "Parent story", parent.id(), "Child"));
        assertThrows(IllegalArgumentException.class, () -> store.addRelatedLink(parent.id(), "Self", parent.id(), "Child"));
        assertThrows(IllegalArgumentException.class, () -> store.addRelatedLink(parent.id(), "Missing", "GRAPH-999", "Child"));
        assertEquals(child.id(), store.searchWorkItems(project.id(), "Child story", 10).get(0).id());
        assertThrows(IllegalArgumentException.class, () -> store.addRelatedLink(parent.id(), "Duplicate", child.id(), "Child"));
    }

    @Test
    void sanitizesRichTextWithoutRemovingPlainText() {
        String safe = RunnerServer.sanitizeRichText("<strong>Bold</strong><ul><li>One</li></ul><a href=\"https://example.com\" onclick=\"alert(1)\">Docs</a><script>alert(1)</script>");
        assertTrue(safe.contains("<strong>Bold</strong>"));
        assertTrue(safe.contains("<ul><li>One</li></ul>"));
        assertTrue(safe.contains("href=\"https://example.com\""));
        assertFalse(safe.contains("script"));
        assertFalse(safe.contains("onclick"));
        assertEquals("plain description", RunnerServer.sanitizeRichText("plain description"));
    }
}
