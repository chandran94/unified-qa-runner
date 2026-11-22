package com.example.unifiedqarunner;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.time.Instant;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.regex.Pattern;
import java.net.URI;

/**
 * Versioned persistence for the user-story and sprint modules.
 *
 * The runner already persists Test Cases and CI/CD settings in qa-runner.json.
 * This store keeps that file as the compatibility boundary, preserves unknown
 * root properties, and writes through a temporary file so a failed write cannot
 * leave the existing runner data partially written.
 */
final class UserStoryStore {
    private static final int CURRENT_SCHEMA_VERSION = 5;
    private static final String CONFIG_FILE = "qa-runner.json";
    private static final String PROJECTS_KEY = "userStoryProjects";
    private static final String EPICS_KEY = "userStoryEpics";
    private static final String STORIES_KEY = "userStories";
    private static final String SPRINTS_KEY = "userStorySprints";
    private static final String LINKS_KEY = "userStoryTestCaseLinks";
    private static final String CREATORS_KEY = "userStoryCreators";
    private static final String ATTACHMENTS_KEY = "userStoryAttachments";
    private static final String RELATED_LINKS_KEY = "userStoryRelatedLinks";
    private static final String ACTIVITY_KEY = "userStoryActivity";
    private static final Pattern PROJECT_KEY = Pattern.compile("[A-Z][A-Z0-9]{1,9}");
    private static final List<String> STATUSES = List.of("To Do", "In Progress", "Testing", "Done");
    private static final List<String> PRIORITIES = List.of("Low", "Medium", "High", "Critical");
    private static final List<String> SPRINT_STATUSES = List.of("Planned", "Active", "Completed");

    record Project(String id, String key, String name, String description, String owner,
                   boolean archived, String createdAt, String updatedAt) {}

    record Epic(String id, String projectId, String key, String name, String description,
                String status, String priority, boolean archived, String createdAt, String updatedAt) {}

    record Story(String id, String projectId, String epicId, String title, String description,
                 String asA, String iWant, String soThat, String acceptanceCriteria,
                 String priority, String status, int storyPoints, String assignee, String reporter,
                 String sprintId, boolean archived, String createdAt, String updatedAt,
                 int businessValue, String risk, String changedById, String changedBy, String changedAt) {}

    record Sprint(String id, String projectId, String name, String goal, String startDate,
                  String endDate, String status, String createdAt, String updatedAt) {}

    record TestCaseLink(String storyId, String testCaseId, String createdAt) {}
    record Creator(String userId, String displayName) {}
    record Attachment(String id, String storyId, String originalName, String storageName, long size,
                      String contentType, String uploaderId, String uploaderName, String createdAt) {}
    record RelatedLink(String id, String storyId, String title, String url, String type,
                       String createdAt, String updatedAt) {}
    record Activity(String id, String storyId, String action, String detail, String actorId,
                    String actorName, String createdAt, String updatedAt, boolean edited, boolean deleted) {
        Activity(String id, String storyId, String action, String detail, String actorId, String actorName, String createdAt) {
            this(id, storyId, action, detail, actorId, actorName, createdAt, createdAt, false, false);
        }
    }
    record WorkItem(String id, String title, String type, String projectId) {}

    private final Path configPath;

    UserStoryStore(Path projectRoot) {
        this.configPath = projectRoot.resolve(CONFIG_FILE).toAbsolutePath().normalize();
        StorageWriteCoordinator.registerPersistentWriter(configPath.getParent(), "qa-runner.json");
    }

    synchronized List<Project> listProjects(boolean includeArchived) throws IOException {
        StoreData data = load();
        return data.projects.stream().filter(p -> includeArchived || !p.archived()).toList();
    }

    synchronized Project createProject(String key, String name, String description, String owner) throws IOException {
        requireProjectKey(key);
        requireText(name, "Project name");
        StoreData data = load();
        if (data.projects.stream().anyMatch(p -> p.key().equalsIgnoreCase(key))) {
            throw new IllegalArgumentException("Project key already exists: " + key);
        }
        String now = Instant.now().toString();
        Project project = new Project(UUID.randomUUID().toString(), key.toUpperCase(), name,
                safe(description), safe(owner), false, now, now);
        data.projects.add(project);
        save(data);
        return project;
    }

    synchronized Project updateProject(String id, String key, String name, String description, String owner)
            throws IOException {
        StoreData data = load();
        Project current = findProject(data, id);
        String nextKey = key == null || key.isBlank() ? current.key() : key.trim().toUpperCase();
        requireProjectKey(nextKey);
        String nextName = name == null || name.isBlank() ? current.name() : name.trim();
        if (data.projects.stream().anyMatch(p -> !p.id().equals(id) && p.key().equalsIgnoreCase(nextKey))) {
            throw new IllegalArgumentException("Project key already exists: " + nextKey);
        }
        Project updated = new Project(current.id(), nextKey, nextName,
                description == null ? current.description() : description.trim(),
                owner == null ? current.owner() : owner.trim(), current.archived(), current.createdAt(), Instant.now().toString());
        replaceProject(data, updated);
        save(data);
        return updated;
    }

    synchronized Project archiveProject(String id, boolean archived) throws IOException {
        StoreData data = load();
        Project current = findProject(data, id);
        Project updated = new Project(current.id(), current.key(), current.name(), current.description(),
                current.owner(), archived, current.createdAt(), Instant.now().toString());
        replaceProject(data, updated);
        save(data);
        return updated;
    }

    synchronized List<Epic> listEpics(String projectId, boolean includeArchived) throws IOException {
        StoreData data = load();
        return data.epics.stream()
                .filter(e -> projectId == null || projectId.equals(e.projectId()))
                .filter(e -> includeArchived || !e.archived()).toList();
    }

    synchronized Epic createEpic(String projectId, String key, String name, String description,
                                 String status, String priority) throws IOException {
        requireText(projectId, "Project");
        requireText(key, "Epic key");
        requireText(name, "Epic name");
        StoreData data = load();
        findProject(data, projectId);
        if (data.epics.stream().anyMatch(e -> e.key().equalsIgnoreCase(key))) {
            throw new IllegalArgumentException("Epic key already exists: " + key);
        }
        String now = Instant.now().toString();
        Epic epic = new Epic(UUID.randomUUID().toString(), projectId, key.trim().toUpperCase(), name.trim(),
                safe(description), normalizeStatus(status), normalizePriority(priority), false, now, now);
        data.epics.add(epic);
        save(data);
        return epic;
    }

    synchronized Epic updateEpic(String id, String key, String name, String description,
                                 String status, String priority) throws IOException {
        StoreData data = load();
        Epic current = findEpic(data, id);
        String nextKey = key == null || key.isBlank() ? current.key() : key.trim().toUpperCase();
        String nextName = name == null || name.isBlank() ? current.name() : name.trim();
        if (data.epics.stream().anyMatch(e -> !e.id().equals(id) && e.key().equalsIgnoreCase(nextKey))) {
            throw new IllegalArgumentException("Epic key already exists: " + nextKey);
        }
        Epic updated = new Epic(current.id(), current.projectId(), nextKey, nextName,
                description == null ? current.description() : description.trim(),
                status == null ? current.status() : normalizeStatus(status),
                priority == null ? current.priority() : normalizePriority(priority),
                current.archived(), current.createdAt(), Instant.now().toString());
        replaceEpic(data, updated);
        save(data);
        return updated;
    }

    synchronized Epic archiveEpic(String id, boolean archived) throws IOException {
        StoreData data = load();
        Epic current = findEpic(data, id);
        Epic updated = new Epic(current.id(), current.projectId(), current.key(), current.name(), current.description(),
                current.status(), current.priority(), archived, current.createdAt(), Instant.now().toString());
        replaceEpic(data, updated);
        save(data);
        return updated;
    }

    synchronized List<Story> listStories(String projectId, String search, int offset, int limit,
                                          boolean includeArchived) throws IOException {
        return listStories(projectId, null, search, offset, limit, includeArchived);
    }

    synchronized List<Story> listStories(String projectId, String sprintId, String search, int offset, int limit,
                                          boolean includeArchived) throws IOException {
        StoreData data = load();
        String query = safe(search).toLowerCase();
        int safeOffset = Math.max(0, offset);
        int safeLimit = Math.min(100, Math.max(1, limit));
        return data.stories.stream()
                .filter(s -> projectId == null || projectId.equals(s.projectId()))
                .filter(s -> sprintId == null || sprintId.equals(s.sprintId()))
                .filter(s -> includeArchived || !s.archived())
                .filter(s -> query.isBlank() || (s.id() + " " + s.title() + " " + s.description()).toLowerCase().contains(query))
                .skip(safeOffset).limit(safeLimit).toList();
    }

    synchronized int countStories(String projectId, String search, boolean includeArchived) throws IOException {
        return countStories(projectId, null, search, includeArchived);
    }

    synchronized int countStories(String projectId, String sprintId, String search, boolean includeArchived) throws IOException {
        String query = safe(search).toLowerCase();
        return (int) load().stories.stream()
                .filter(s -> projectId == null || projectId.equals(s.projectId()))
                .filter(s -> sprintId == null || sprintId.equals(s.sprintId()))
                .filter(s -> includeArchived || !s.archived())
                .filter(s -> query.isBlank() || (s.id() + " " + s.title() + " " + s.description()).toLowerCase().contains(query))
                .count();
    }

    synchronized Story createStory(String projectId, String epicId, String title, String description,
                                   String asA, String iWant, String soThat, String acceptanceCriteria,
                                   String priority, String status, int storyPoints, String assignee, String reporter)
            throws IOException {
        return createStory(projectId, epicId, title, description, asA, iWant, soThat, acceptanceCriteria,
                priority, status, storyPoints, assignee, reporter, legacyCreatorId(reporter), safe(reporter));
    }

    synchronized Story createStory(String projectId, String epicId, String title, String description,
                                   String asA, String iWant, String soThat, String acceptanceCriteria,
                                   String priority, String status, int storyPoints, String assignee, String reporter,
                                   String creatorId, String creatorName) throws IOException {
        StoreData data = load();
        Project project = findProject(data, projectId);
        validateEpic(data, projectId, epicId);
        requireText(title, "Story title");
        validatePoints(storyPoints);
        String id = nextStoryId(data, project.key());
        String now = Instant.now().toString();
        Story story = new Story(id, projectId, safe(epicId), title.trim(), safe(description), safe(asA), safe(iWant),
                safe(soThat), safe(acceptanceCriteria), normalizePriority(priority), normalizeStatus(status), storyPoints,
                safe(assignee), safe(reporter), "", false, now, now, 0, "Medium", safe(creatorId), safe(creatorName), now);
        data.stories.add(story);
        data.creators.put(id, new Creator(safe(creatorId), safe(creatorName)));
        data.activity.add(new Activity(UUID.randomUUID().toString(), id, "Created", "Story created", safe(creatorId), safe(creatorName), now));
        save(data);
        return story;
    }

    synchronized Story updateStory(String id, String epicId, String title, String description,
                                   String asA, String iWant, String soThat, String acceptanceCriteria,
                                   String priority, String status, int storyPoints, String assignee, String reporter)
            throws IOException {
        StoreData existing = load();
        Story current = findStory(existing, id);
        return updateStory(id, epicId, title, description, asA, iWant, soThat, acceptanceCriteria, priority, status,
                storyPoints, assignee, reporter, current.businessValue(), current.risk(), current.changedById(), current.changedBy(), current.sprintId());
    }

    synchronized Story updateStory(String id, String epicId, String title, String description,
                                   String asA, String iWant, String soThat, String acceptanceCriteria,
                                   String priority, String status, int storyPoints, String assignee, String reporter,
                                   int businessValue, String risk, String changedById, String changedBy)
            throws IOException {
        StoreData existing = load();
        return updateStory(id, epicId, title, description, asA, iWant, soThat, acceptanceCriteria, priority, status,
                storyPoints, assignee, reporter, businessValue, risk, changedById, changedBy, findStory(existing, id).sprintId());
    }

    synchronized Story updateStory(String id, String epicId, String title, String description,
                                   String asA, String iWant, String soThat, String acceptanceCriteria,
                                   String priority, String status, int storyPoints, String assignee, String reporter,
                                   int businessValue, String risk, String changedById, String changedBy, String sprintId)
            throws IOException {
        StoreData data = load();
        Story current = findStory(data, id);
        validateEpic(data, current.projectId(), epicId == null ? current.epicId() : epicId);
        validateSprint(data, current.projectId(), sprintId);
        validatePoints(storyPoints);
        Story updated = new Story(current.id(), current.projectId(), epicId == null ? current.epicId() : safe(epicId),
                title == null || title.isBlank() ? current.title() : title.trim(),
                description == null ? current.description() : description.trim(),
                asA == null ? current.asA() : asA.trim(), iWant == null ? current.iWant() : iWant.trim(),
                soThat == null ? current.soThat() : soThat.trim(),
                acceptanceCriteria == null ? current.acceptanceCriteria() : acceptanceCriteria.trim(),
                priority == null ? current.priority() : normalizePriority(priority),
                status == null ? current.status() : normalizeStatus(status), storyPoints,
                assignee == null ? current.assignee() : assignee.trim(), current.reporter(), safe(sprintId),
                current.archived(), current.createdAt(), Instant.now().toString(), businessValue, risk == null || risk.isBlank() ? current.risk() : risk.trim(),
                safe(changedById), safe(changedBy), Instant.now().toString());
        replaceStory(data, updated);
        data.activity.add(new Activity(UUID.randomUUID().toString(), id, "Changed", "Story fields updated", updated.changedById(), updated.changedBy(), updated.changedAt()));
        save(data);
        return updated;
    }

    synchronized Story archiveStory(String id, boolean archived) throws IOException {
        StoreData data = load();
        Story current = findStory(data, id);
        Story updated = new Story(current.id(), current.projectId(), current.epicId(), current.title(), current.description(),
                current.asA(), current.iWant(), current.soThat(), current.acceptanceCriteria(), current.priority(), current.status(),
                current.storyPoints(), current.assignee(), current.reporter(), current.sprintId(), archived, current.createdAt(), Instant.now().toString(), current.businessValue(), current.risk(), current.changedById(), current.changedBy(), Instant.now().toString());
        replaceStory(data, updated);
        save(data);
        return updated;
    }

    synchronized List<Sprint> listSprints(String projectId, boolean includeCompleted) throws IOException {
        return load().sprints.stream()
                .filter(s -> projectId == null || projectId.equals(s.projectId()))
                .filter(s -> includeCompleted || !s.status().equals("Completed"))
                .toList();
    }

    synchronized Sprint createSprint(String projectId, String name, String goal, String startDate, String endDate) throws IOException {
        StoreData data = load();
        Project project = findProject(data, projectId);
        requireText(name, "Sprint name");
        validateDates(startDate, endDate);
        String now = Instant.now().toString();
        String id = nextSprintId(data, project.key());
        Sprint sprint = new Sprint(id, projectId, name.trim(), safe(goal), startDate.trim(), endDate.trim(), "Planned", now, now);
        data.sprints.add(sprint);
        save(data);
        return sprint;
    }

    synchronized Sprint updateSprint(String id, String name, String goal, String startDate, String endDate) throws IOException {
        StoreData data = load();
        Sprint current = findSprint(data, id);
        validateDates(startDate == null ? current.startDate() : startDate, endDate == null ? current.endDate() : endDate);
        String nextName = name == null || name.isBlank() ? current.name() : name.trim();
        Sprint updated = new Sprint(current.id(), current.projectId(), nextName,
                goal == null ? current.goal() : goal.trim(),
                startDate == null ? current.startDate() : startDate.trim(),
                endDate == null ? current.endDate() : endDate.trim(), current.status(), current.createdAt(), Instant.now().toString());
        replaceSprint(data, updated);
        save(data);
        return updated;
    }

    synchronized Sprint startSprint(String id) throws IOException {
        return transitionSprint(id, "Active");
    }

    synchronized Sprint completeSprint(String id) throws IOException {
        return transitionSprint(id, "Completed");
    }

    synchronized Story assignStoryToSprint(String sprintId, String storyId) throws IOException {
        StoreData data = load();
        Sprint sprint = findSprint(data, sprintId);
        if (sprint.status().equals("Completed")) throw new IllegalArgumentException("Completed sprints cannot receive stories.");
        Story current = findStory(data, storyId);
        if (!current.projectId().equals(sprint.projectId())) throw new IllegalArgumentException("Story and sprint must belong to the same project.");
        Story updated = withSprint(current, sprintId);
        replaceStory(data, updated);
        save(data);
        return updated;
    }

    synchronized Story removeStoryFromSprint(String storyId) throws IOException {
        StoreData data = load();
        Story current = findStory(data, storyId);
        Story updated = withSprint(current, "");
        replaceStory(data, updated);
        save(data);
        return updated;
    }

    synchronized Story updateStoryStatus(String storyId, String status) throws IOException {
        return updateStoryStatus(storyId, status, null);
    }

    synchronized Story updateStoryStatus(String storyId, String status, String expectedProjectId) throws IOException {
        StoreData data = load();
        Story current = findStory(data, storyId);
        if (expectedProjectId != null && !expectedProjectId.isBlank() && !expectedProjectId.equals(current.projectId())) {
            throw new IllegalArgumentException("Story does not belong to the selected project.");
        }
        String nextStatus = normalizeStatus(status);
        String changedAt = Instant.now().toString();
        Story updated = new Story(current.id(), current.projectId(), current.epicId(), current.title(), current.description(), current.asA(), current.iWant(), current.soThat(), current.acceptanceCriteria(), current.priority(), nextStatus, current.storyPoints(), current.assignee(), current.reporter(), current.sprintId(), current.archived(), current.createdAt(), changedAt, current.businessValue(), current.risk(), current.changedById(), current.changedBy(), changedAt);
        replaceStory(data, updated);
        data.activity.add(new Activity(UUID.randomUUID().toString(), storyId, "Status changed", current.status() + " → " + nextStatus, current.changedById(), current.changedBy(), changedAt));
        save(data);
        return updated;
    }

    synchronized List<TestCaseLink> listTestCaseLinks(String storyId) throws IOException {
        StoreData data = load();
        findStory(data, storyId);
        return data.links.stream().filter(link -> link.storyId().equals(storyId)).toList();
    }

    synchronized Creator creatorFor(String storyId) throws IOException {
        StoreData data = load();
        findStory(data, storyId);
        Creator creator = data.creators.getOrDefault(storyId, new Creator("legacy:unknown", "Unknown"));
        return creator.displayName().isBlank() ? new Creator(creator.userId().isBlank() ? "legacy:unknown" : creator.userId(), "Unknown") : creator;
    }

    synchronized List<Activity> listActivity(String storyId) throws IOException {
        StoreData data = load(); findStory(data, storyId);
        return data.activity.stream().filter(a -> a.storyId().equals(storyId))
                .sorted(java.util.Comparator.comparing(Activity::createdAt).reversed()).toList();
    }

    synchronized Activity addComment(String storyId, String comment, String actorId, String actorName) throws IOException {
        StoreData data = load(); findStory(data, storyId); requireText(comment, "Comment");
        String now = Instant.now().toString();
        Activity activity = new Activity(UUID.randomUUID().toString(), storyId, "Comment", safe(comment), safe(actorId), safe(actorName), now, now, false, false);
        data.activity.add(activity); save(data); return activity;
    }

    synchronized Activity updateComment(String storyId, String commentId, String comment, String actorId, String actorName) throws IOException {
        StoreData data = load(); findStory(data, storyId); requireText(comment, "Comment");
        Activity current = findComment(data, storyId, commentId);
        if (current.deleted()) throw new IllegalArgumentException("Deleted comments cannot be edited.");
        String now = Instant.now().toString();
        Activity updated = new Activity(current.id(), current.storyId(), "Comment", comment.trim(), current.actorId(), current.actorName(), current.createdAt(), now, true, false);
        replaceActivity(data, updated); save(data); return updated;
    }

    synchronized Activity deleteComment(String storyId, String commentId, String actorId, String actorName) throws IOException {
        StoreData data = load(); findStory(data, storyId);
        Activity current = findComment(data, storyId, commentId);
        if (current.deleted()) return current;
        String now = Instant.now().toString();
        Activity deleted = new Activity(current.id(), current.storyId(), "Comment", "This comment was deleted", current.actorId(), current.actorName(), current.createdAt(), now, current.edited(), true);
        replaceActivity(data, deleted); save(data); return deleted;
    }

    synchronized void recordActivity(String storyId, String action, String detail, String actorId, String actorName) throws IOException {
        StoreData data = load(); findStory(data, storyId);
        data.activity.add(new Activity(UUID.randomUUID().toString(), storyId, safe(action), safe(detail), safe(actorId), safe(actorName), Instant.now().toString()));
        save(data);
    }

    synchronized List<Attachment> listAttachments(String storyId) throws IOException {
        StoreData data = load();
        findStory(data, storyId);
        return data.attachments.stream().filter(a -> a.storyId().equals(storyId)).toList();
    }

    synchronized Attachment addAttachment(String storyId, String originalName, String storageName, long size,
                                          String contentType, String uploaderId, String uploaderName) throws IOException {
        StoreData data = load();
        findStory(data, storyId);
        String now = Instant.now().toString();
        Attachment attachment = new Attachment(UUID.randomUUID().toString(), storyId, safe(originalName), safe(storageName), size,
                safe(contentType), safe(uploaderId), safe(uploaderName), now);
        data.attachments.add(attachment);
        save(data);
        return attachment;
    }

    synchronized Attachment findAttachment(String attachmentId) throws IOException {
        return load().attachments.stream().filter(a -> a.id().equals(attachmentId)).findFirst()
                .orElseThrow(() -> new IllegalArgumentException("Attachment not found."));
    }

    synchronized void removeAttachment(String attachmentId) throws IOException {
        StoreData data = load();
        if (!data.attachments.removeIf(a -> a.id().equals(attachmentId))) throw new IllegalArgumentException("Attachment not found.");
        save(data);
    }

    synchronized List<RelatedLink> listRelatedLinks(String storyId) throws IOException {
        StoreData data = load();
        findStory(data, storyId);
        return data.relatedLinks.stream().filter(link -> link.storyId().equals(storyId)).toList();
    }

    synchronized List<WorkItem> searchWorkItems(String projectId, String query, int limit) throws IOException {
        StoreData data = load();
        String needle = safe(query).toLowerCase();
        int max = Math.min(100, Math.max(1, limit));
        List<WorkItem> result = new ArrayList<>();
        data.projects.stream().filter(p -> projectId == null || projectId.isBlank() || p.id().equals(projectId))
                .filter(p -> matchesWorkItem(needle, p.id(), p.key(), p.name())).limit(max).forEach(p -> result.add(new WorkItem(p.key(), p.name(), "Project", p.id())));
        data.epics.stream().filter(e -> projectId == null || projectId.isBlank() || e.projectId().equals(projectId))
                .filter(e -> matchesWorkItem(needle, e.id(), e.key(), e.name())).limit(max).forEach(e -> result.add(new WorkItem(e.key(), e.name(), "Epic / Feature", e.projectId())));
        data.stories.stream().filter(s -> projectId == null || projectId.isBlank() || s.projectId().equals(projectId))
                .filter(s -> matchesWorkItem(needle, s.id(), s.id(), s.title())).limit(max).forEach(s -> result.add(new WorkItem(s.id(), s.title(), "User Story", s.projectId())));
        return result.stream().limit(max).toList();
    }

    synchronized RelatedLink addRelatedLink(String storyId, String title, String url, String type) throws IOException {
        StoreData data = load();
        Story source = findStory(data, storyId);
        String normalizedType = normalizeLinkType(type);
        String normalizedUrl = validateTarget(data, source, url, normalizedType);
        requireText(title, "Link title");
        if (data.relatedLinks.stream().anyMatch(link -> link.storyId().equals(storyId) && link.url().equalsIgnoreCase(normalizedUrl))) {
            throw new IllegalArgumentException("This URL is already linked to the story.");
        }
        String now = Instant.now().toString();
        RelatedLink link = new RelatedLink(UUID.randomUUID().toString(), storyId, safe(title), normalizedUrl, normalizedType, now, now);
        data.relatedLinks.add(link);
        save(data);
        return link;
    }

    synchronized RelatedLink updateRelatedLink(String linkId, String title, String url, String type) throws IOException {
        StoreData data = load();
        RelatedLink current = data.relatedLinks.stream().filter(link -> link.id().equals(linkId)).findFirst()
                .orElseThrow(() -> new IllegalArgumentException("Related link not found."));
        Story source = findStory(data, current.storyId());
        String normalizedType = normalizeLinkType(type);
        String normalizedUrl = validateTarget(data, source, url, normalizedType);
        requireText(title, "Link title");
        if (data.relatedLinks.stream().anyMatch(link -> !link.id().equals(linkId) && link.storyId().equals(current.storyId()) && link.url().equalsIgnoreCase(normalizedUrl))) {
            throw new IllegalArgumentException("This URL is already linked to the story.");
        }
        RelatedLink updated = new RelatedLink(current.id(), current.storyId(), safe(title), normalizedUrl, normalizedType, current.createdAt(), Instant.now().toString());
        for (int i = 0; i < data.relatedLinks.size(); i++) if (data.relatedLinks.get(i).id().equals(linkId)) data.relatedLinks.set(i, updated);
        save(data);
        return updated;
    }

    synchronized void removeRelatedLink(String linkId) throws IOException {
        StoreData data = load();
        if (!data.relatedLinks.removeIf(link -> link.id().equals(linkId))) throw new IllegalArgumentException("Related link not found.");
        save(data);
    }

    synchronized RelatedLink findRelatedLink(String linkId) throws IOException {
        return load().relatedLinks.stream().filter(link -> link.id().equals(linkId)).findFirst().orElseThrow(() -> new IllegalArgumentException("Related link not found."));
    }

    synchronized TestCaseLink linkTestCase(String storyId, String testCaseId) throws IOException {
        StoreData data = load();
        findStory(data, storyId);
        requireText(testCaseId, "Test case ID");
        if (data.links.stream().anyMatch(link -> link.storyId().equals(storyId) && link.testCaseId().equals(testCaseId))) {
            throw new IllegalArgumentException("Test case is already linked to this story.");
        }
        TestCaseLink link = new TestCaseLink(storyId, testCaseId, Instant.now().toString());
        data.links.add(link);
        save(data);
        return link;
    }

    synchronized void unlinkTestCase(String storyId, String testCaseId) throws IOException {
        StoreData data = load();
        findStory(data, storyId);
        data.links.removeIf(link -> link.storyId().equals(storyId) && link.testCaseId().equals(testCaseId));
        save(data);
    }

    synchronized List<TestCaseLink> listTestCaseLinks() throws IOException {
        return load().links;
    }

    private StoreData load() throws IOException {
        Map<String, Object> root = readRoot();
        int version = number(root.get("userStorySchemaVersion"));
        List<Project> projects = readProjects(root.get(PROJECTS_KEY));
        List<Epic> epics = readEpics(root.get(EPICS_KEY));
        List<Story> stories = readStories(root.get(STORIES_KEY));
        List<Sprint> sprints = readSprints(root.get(SPRINTS_KEY));
        List<TestCaseLink> links = readLinks(root.get(LINKS_KEY));
        Map<String, Creator> creators = readCreators(root.get(CREATORS_KEY));
        List<Attachment> attachments = readAttachments(root.get(ATTACHMENTS_KEY));
        List<RelatedLink> relatedLinks = readRelatedLinks(root.get(RELATED_LINKS_KEY));
        List<Activity> activity = readActivity(root.get(ACTIVITY_KEY));
        for (Story story : stories) {
            creators.putIfAbsent(story.id(), new Creator(legacyCreatorId(story.reporter()), safe(story.reporter())));
        }
        if (version < CURRENT_SCHEMA_VERSION) {
            // Non-destructive migration: initialize new collections and retain all existing runner data.
            root.put("userStorySchemaVersion", CURRENT_SCHEMA_VERSION);
            root.putIfAbsent(PROJECTS_KEY, new ArrayList<>());
            root.putIfAbsent(EPICS_KEY, new ArrayList<>());
            root.putIfAbsent(STORIES_KEY, new ArrayList<>());
            root.putIfAbsent(SPRINTS_KEY, new ArrayList<>());
            root.putIfAbsent(LINKS_KEY, new ArrayList<>());
            root.putIfAbsent(CREATORS_KEY, new LinkedHashMap<>());
            root.putIfAbsent(ATTACHMENTS_KEY, new ArrayList<>());
            root.putIfAbsent(RELATED_LINKS_KEY, new ArrayList<>());
            root.putIfAbsent(ACTIVITY_KEY, new ArrayList<>());
            root.put(CREATORS_KEY, creators.entrySet().stream().collect(java.util.stream.Collectors.toMap(Map.Entry::getKey, e -> creatorMap(e.getValue()), (a, b) -> a, LinkedHashMap::new)));
            writeRoot(root);
        }
        return new StoreData(root, new ArrayList<>(projects), new ArrayList<>(epics), new ArrayList<>(stories), new ArrayList<>(sprints), new ArrayList<>(links), creators, new ArrayList<>(attachments), new ArrayList<>(relatedLinks), new ArrayList<>(activity));
    }

    private void save(StoreData data) throws IOException {
        data.root.put("userStorySchemaVersion", CURRENT_SCHEMA_VERSION);
        data.root.put(PROJECTS_KEY, data.projects.stream().map(UserStoryStore::projectMap).toList());
        data.root.put(EPICS_KEY, data.epics.stream().map(UserStoryStore::epicMap).toList());
        data.root.put(STORIES_KEY, data.stories.stream().map(UserStoryStore::storyMap).toList());
        data.root.put(SPRINTS_KEY, data.sprints.stream().map(UserStoryStore::sprintMap).toList());
        data.root.put(LINKS_KEY, data.links.stream().map(UserStoryStore::linkMap).toList());
        data.root.put(CREATORS_KEY, data.creators.entrySet().stream().collect(java.util.stream.Collectors.toMap(Map.Entry::getKey, e -> creatorMap(e.getValue()), (a, b) -> a, LinkedHashMap::new)));
        data.root.put(ATTACHMENTS_KEY, data.attachments.stream().map(UserStoryStore::attachmentMap).toList());
        data.root.put(RELATED_LINKS_KEY, data.relatedLinks.stream().map(UserStoryStore::relatedLinkMap).toList());
        data.root.put(ACTIVITY_KEY, data.activity.stream().map(UserStoryStore::activityMap).toList());
        writeRoot(data.root);
    }

    @SuppressWarnings("unchecked")
    private Map<String, Object> readRoot() throws IOException {
        if (!Files.exists(configPath)) return new LinkedHashMap<>();
        Object parsed = JsonParser.parse(Files.readString(configPath, StandardCharsets.UTF_8));
        if (parsed instanceof Map<?, ?> map) return new LinkedHashMap<>((Map<String, Object>) map);
        throw new IOException("qa-runner.json must contain a JSON object.");
    }

    private void writeRoot(Map<String, Object> root) throws IOException {
        StorageWriteCoordinator.coordinatedWrite(configPath.getParent(), "qa-runner.json", () -> {
            Files.createDirectories(configPath.getParent());
            Path temp = configPath.resolveSibling(configPath.getFileName() + ".user-stories.tmp");
            Files.writeString(temp, CiCdConfig.toJson(root), StandardCharsets.UTF_8);
            try {
                Files.move(temp, configPath, StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.ATOMIC_MOVE);
            } catch (java.nio.file.AtomicMoveNotSupportedException e) {
                Files.move(temp, configPath, StandardCopyOption.REPLACE_EXISTING);
            }
        });
    }

    private static List<Project> readProjects(Object raw) {
        List<Project> result = new ArrayList<>();
        if (raw instanceof List<?> list) for (Object item : list) if (item instanceof Map<?, ?> m) {
            result.add(new Project(text(m, "id", UUID.randomUUID().toString()), text(m, "key", ""), text(m, "name", ""),
                    text(m, "description", ""), text(m, "owner", ""), bool(m, "archived"), text(m, "createdAt", ""), text(m, "updatedAt", "")));
        }
        return result;
    }

    private static List<Epic> readEpics(Object raw) {
        List<Epic> result = new ArrayList<>();
        if (raw instanceof List<?> list) for (Object item : list) if (item instanceof Map<?, ?> m) {
            result.add(new Epic(text(m, "id", UUID.randomUUID().toString()), text(m, "projectId", ""), text(m, "key", ""),
                    text(m, "name", ""), text(m, "description", ""), text(m, "status", "To Do"), text(m, "priority", "Medium"),
                    bool(m, "archived"), text(m, "createdAt", ""), text(m, "updatedAt", "")));
        }
        return result;
    }

    private static List<Story> readStories(Object raw) {
        List<Story> result = new ArrayList<>();
        if (raw instanceof List<?> list) for (Object item : list) if (item instanceof Map<?, ?> m) {
            result.add(new Story(text(m, "id", ""), text(m, "projectId", ""), text(m, "epicId", ""), text(m, "title", ""),
                    text(m, "description", ""), text(m, "asA", ""), text(m, "iWant", ""), text(m, "soThat", ""),
                    text(m, "acceptanceCriteria", ""), text(m, "priority", "Medium"), text(m, "status", "To Do"),
                    number(m.get("storyPoints")), text(m, "assignee", ""), text(m, "reporter", ""), text(m, "sprintId", ""), bool(m, "archived"),
                    text(m, "createdAt", ""), text(m, "updatedAt", ""), number(m.get("businessValue")), text(m, "risk", "Medium"),
                    text(m, "changedById", "legacy:unknown"), text(m, "changedBy", "Unknown"), text(m, "changedAt", text(m, "updatedAt", ""))));
        }
        return result;
    }

    private static List<Sprint> readSprints(Object raw) {
        List<Sprint> result = new ArrayList<>();
        if (raw instanceof List<?> list) for (Object item : list) if (item instanceof Map<?, ?> m) {
            result.add(new Sprint(text(m, "id", ""), text(m, "projectId", ""), text(m, "name", ""), text(m, "goal", ""),
                    text(m, "startDate", ""), text(m, "endDate", ""), text(m, "status", "Planned"), text(m, "createdAt", ""), text(m, "updatedAt", "")));
        }
        return result;
    }

    private static List<TestCaseLink> readLinks(Object raw) {
        List<TestCaseLink> result = new ArrayList<>();
        if (raw instanceof List<?> list) for (Object item : list) if (item instanceof Map<?, ?> m) {
            String storyId = text(m, "storyId", "");
            String testCaseId = text(m, "testCaseId", "");
            if (!storyId.isBlank() && !testCaseId.isBlank()) result.add(new TestCaseLink(storyId, testCaseId, text(m, "createdAt", "")));
        }
        return result;
    }

    private static Map<String, Creator> readCreators(Object raw) {
        Map<String, Creator> result = new LinkedHashMap<>();
        if (raw instanceof Map<?, ?> map) for (Map.Entry<?, ?> entry : map.entrySet()) {
            if (entry.getKey() == null || !(entry.getValue() instanceof Map<?, ?> value)) continue;
            result.put(entry.getKey().toString(), new Creator(text(value, "userId", ""), text(value, "displayName", "")));
        }
        return result;
    }

    private static List<Attachment> readAttachments(Object raw) {
        List<Attachment> result = new ArrayList<>();
        if (raw instanceof List<?> list) for (Object item : list) if (item instanceof Map<?, ?> m) {
            result.add(new Attachment(text(m, "id", UUID.randomUUID().toString()), text(m, "storyId", ""), text(m, "originalName", ""),
                    text(m, "storageName", ""), longNumber(m.get("size")), text(m, "contentType", "application/octet-stream"),
                    text(m, "uploaderId", "legacy:unknown"), text(m, "uploaderName", "Unknown"), text(m, "createdAt", "")));
        }
        return result;
    }

    private static List<RelatedLink> readRelatedLinks(Object raw) {
        List<RelatedLink> result = new ArrayList<>();
        if (raw instanceof List<?> list) for (Object item : list) if (item instanceof Map<?, ?> m) {
            result.add(new RelatedLink(text(m, "id", UUID.randomUUID().toString()), text(m, "storyId", ""), text(m, "title", ""), text(m, "url", ""), text(m, "type", "Other"), text(m, "createdAt", ""), text(m, "updatedAt", "")));
        }
        return result;
    }
    private static List<Activity> readActivity(Object raw) {
        List<Activity> result = new ArrayList<>();
        if (raw instanceof List<?> list) for (Object item : list) if (item instanceof Map<?, ?> m) {
            String createdAt = text(m, "createdAt", "");
            result.add(new Activity(text(m, "id", UUID.randomUUID().toString()), text(m, "storyId", ""), text(m, "action", "Changed"), text(m, "detail", ""), text(m, "actorId", "legacy:unknown"), text(m, "actorName", "Unknown"), createdAt, text(m, "updatedAt", createdAt), bool(m, "edited"), bool(m, "deleted")));
        }
        return result;
    }

    private static Map<String, Object> projectMap(Project p) { return map("id", p.id(), "key", p.key(), "name", p.name(), "description", p.description(), "owner", p.owner(), "archived", p.archived(), "createdAt", p.createdAt(), "updatedAt", p.updatedAt()); }
    private static Map<String, Object> epicMap(Epic e) { return map("id", e.id(), "projectId", e.projectId(), "key", e.key(), "name", e.name(), "description", e.description(), "status", e.status(), "priority", e.priority(), "archived", e.archived(), "createdAt", e.createdAt(), "updatedAt", e.updatedAt()); }
    private static Map<String, Object> storyMap(Story s) { return map("id", s.id(), "projectId", s.projectId(), "epicId", s.epicId(), "title", s.title(), "description", s.description(), "asA", s.asA(), "iWant", s.iWant(), "soThat", s.soThat(), "acceptanceCriteria", s.acceptanceCriteria(), "priority", s.priority(), "status", s.status(), "storyPoints", s.storyPoints(), "businessValue", s.businessValue(), "risk", s.risk(), "assignee", s.assignee(), "reporter", s.reporter(), "sprintId", s.sprintId(), "archived", s.archived(), "createdAt", s.createdAt(), "updatedAt", s.updatedAt(), "changedById", s.changedById(), "changedBy", s.changedBy(), "changedAt", s.changedAt()); }
    private static Map<String, Object> sprintMap(Sprint s) { return map("id", s.id(), "projectId", s.projectId(), "name", s.name(), "goal", s.goal(), "startDate", s.startDate(), "endDate", s.endDate(), "status", s.status(), "createdAt", s.createdAt(), "updatedAt", s.updatedAt()); }
    private static Map<String, Object> linkMap(TestCaseLink link) { return map("storyId", link.storyId(), "testCaseId", link.testCaseId(), "createdAt", link.createdAt()); }
    private static Map<String, Object> creatorMap(Creator creator) { return map("userId", creator.userId(), "displayName", creator.displayName()); }
    private static Map<String, Object> attachmentMap(Attachment attachment) { return map("id", attachment.id(), "storyId", attachment.storyId(), "originalName", attachment.originalName(), "storageName", attachment.storageName(), "size", attachment.size(), "contentType", attachment.contentType(), "uploaderId", attachment.uploaderId(), "uploaderName", attachment.uploaderName(), "createdAt", attachment.createdAt()); }
    private static Map<String, Object> relatedLinkMap(RelatedLink link) { return map("id", link.id(), "storyId", link.storyId(), "title", link.title(), "url", link.url(), "type", link.type(), "createdAt", link.createdAt(), "updatedAt", link.updatedAt()); }
    private static Map<String, Object> activityMap(Activity activity) { return map("id", activity.id(), "storyId", activity.storyId(), "action", activity.action(), "detail", activity.detail(), "actorId", activity.actorId(), "actorName", activity.actorName(), "createdAt", activity.createdAt(), "updatedAt", activity.updatedAt(), "edited", activity.edited(), "deleted", activity.deleted()); }
    private static Map<String, Object> map(Object... values) { Map<String, Object> m = new LinkedHashMap<>(); for (int i = 0; i < values.length; i += 2) m.put((String) values[i], values[i + 1]); return m; }

    private static String nextStoryId(StoreData data, String projectKey) {
        int max = data.stories.stream().filter(s -> s.id().startsWith(projectKey + "-")).mapToInt(s -> {
            try { return Integer.parseInt(s.id().substring(projectKey.length() + 1)); } catch (NumberFormatException e) { return 0; }
        }).max().orElse(0);
        return projectKey + "-" + (max + 1);
    }

    private static String nextSprintId(StoreData data, String projectKey) {
        int max = data.sprints.stream().filter(s -> s.id().startsWith(projectKey + "-S"))
                .mapToInt(s -> { try { return Integer.parseInt(s.id().substring((projectKey + "-S").length())); } catch (NumberFormatException e) { return 0; } }).max().orElse(0);
        return projectKey + "-S" + (max + 1);
    }

    private static Project findProject(StoreData d, String id) { return d.projects.stream().filter(p -> p.id().equals(id)).findFirst().orElseThrow(() -> new IllegalArgumentException("Project not found: " + id)); }
    private static Epic findEpic(StoreData d, String id) { return d.epics.stream().filter(e -> e.id().equals(id)).findFirst().orElseThrow(() -> new IllegalArgumentException("Epic not found: " + id)); }
    private static Story findStory(StoreData d, String id) { return d.stories.stream().filter(s -> s.id().equals(id)).findFirst().orElseThrow(() -> new IllegalArgumentException("Story not found: " + id)); }
    private static Activity findComment(StoreData d, String storyId, String commentId) {
        return d.activity.stream().filter(a -> a.id().equals(commentId) && a.storyId().equals(storyId) && a.action().equals("Comment"))
                .findFirst().orElseThrow(() -> new IllegalArgumentException("Comment not found: " + commentId));
    }
    private static void replaceActivity(StoreData d, Activity value) {
        for (int i = 0; i < d.activity.size(); i++) if (d.activity.get(i).id().equals(value.id())) { d.activity.set(i, value); return; }
        throw new IllegalArgumentException("Comment not found: " + value.id());
    }
    private static Sprint findSprint(StoreData d, String id) { return d.sprints.stream().filter(s -> s.id().equals(id)).findFirst().orElseThrow(() -> new IllegalArgumentException("Sprint not found: " + id)); }
    private static void replaceProject(StoreData d, Project value) { for (int i = 0; i < d.projects.size(); i++) if (d.projects.get(i).id().equals(value.id())) { d.projects.set(i, value); return; } }
    private static void replaceEpic(StoreData d, Epic value) { for (int i = 0; i < d.epics.size(); i++) if (d.epics.get(i).id().equals(value.id())) { d.epics.set(i, value); return; } }
    private static void replaceStory(StoreData d, Story value) { for (int i = 0; i < d.stories.size(); i++) if (d.stories.get(i).id().equals(value.id())) { d.stories.set(i, value); return; } }
    private static void replaceSprint(StoreData d, Sprint value) { for (int i = 0; i < d.sprints.size(); i++) if (d.sprints.get(i).id().equals(value.id())) { d.sprints.set(i, value); return; } }
    private static void validateSprint(StoreData d, String projectId, String sprintId) {
        if (sprintId == null || sprintId.isBlank()) return;
        Sprint sprint = findSprint(d, sprintId);
        if (!projectId.equals(sprint.projectId())) throw new IllegalArgumentException("Story and sprint must belong to the same project.");
        if (sprint.status().equals("Completed")) throw new IllegalArgumentException("Completed sprints cannot receive stories.");
    }
    private static Story withSprint(Story s, String sprintId) { String now = Instant.now().toString(); return new Story(s.id(), s.projectId(), s.epicId(), s.title(), s.description(), s.asA(), s.iWant(), s.soThat(), s.acceptanceCriteria(), s.priority(), s.status(), s.storyPoints(), s.assignee(), s.reporter(), sprintId, s.archived(), s.createdAt(), now, s.businessValue(), s.risk(), s.changedById(), s.changedBy(), now); }
    private Sprint transitionSprint(String id, String target) throws IOException {
        StoreData data = load();
        Sprint current = findSprint(data, id);
        if (target.equals("Active")) {
            if (!current.status().equals("Planned")) throw new IllegalArgumentException("Only Planned sprints can be started.");
            if (data.sprints.stream().anyMatch(s -> s.projectId().equals(current.projectId()) && s.status().equals("Active"))) throw new IllegalArgumentException("Only one active sprint is allowed per project.");
        } else if (!current.status().equals("Active")) {
            throw new IllegalArgumentException("Only Active sprints can be completed.");
        }
        Sprint updated = new Sprint(current.id(), current.projectId(), current.name(), current.goal(), current.startDate(), current.endDate(), target, current.createdAt(), Instant.now().toString());
        replaceSprint(data, updated);
        save(data);
        return updated;
    }
    private static void validateDates(String startDate, String endDate) {
        try {
            java.time.LocalDate start = java.time.LocalDate.parse(startDate);
            java.time.LocalDate end = java.time.LocalDate.parse(endDate);
            if (end.isBefore(start)) throw new IllegalArgumentException("Sprint end date must be on or after the start date.");
        } catch (java.time.format.DateTimeParseException e) { throw new IllegalArgumentException("Sprint dates must use YYYY-MM-DD."); }
    }
    private static void validateEpic(StoreData d, String projectId, String epicId) { if (epicId != null && !epicId.isBlank() && d.epics.stream().noneMatch(e -> e.id().equals(epicId) && e.projectId().equals(projectId))) throw new IllegalArgumentException("Epic does not belong to the selected project."); }
    private static void requireProjectKey(String key) { if (key == null || !PROJECT_KEY.matcher(key.trim().toUpperCase()).matches()) throw new IllegalArgumentException("Project key must be 2-10 letters/numbers and start with a letter."); }
    private static void requireText(String value, String field) { if (value == null || value.isBlank()) throw new IllegalArgumentException(field + " is required."); }
    private static void validatePoints(int points) { if (points < 0 || points > 1000) throw new IllegalArgumentException("Story points must be between 0 and 1000."); }
    private static String normalizeStatus(String value) { String v = value == null || value.isBlank() ? "To Do" : value.trim(); if (!STATUSES.contains(v)) throw new IllegalArgumentException("Invalid story status: " + v); return v; }
    private static String normalizePriority(String value) { String v = value == null || value.isBlank() ? "Medium" : value.trim(); if (!PRIORITIES.contains(v)) throw new IllegalArgumentException("Invalid priority: " + v); return v; }
    private static String safe(String value) { return value == null ? "" : value.trim(); }
    private static String legacyCreatorId(String value) { return safe(value).isBlank() ? "legacy:unknown" : "legacy:" + Integer.toHexString(safe(value).hashCode()); }
    private static String text(Map<?, ?> m, String key, String fallback) { Object v = m.get(key); return v == null ? fallback : v.toString(); }
    private static boolean bool(Map<?, ?> m, String key) { return Boolean.TRUE.equals(m.get(key)); }
    private static int number(Object v) { return v instanceof Number n ? n.intValue() : 0; }
    private static long longNumber(Object v) { return v instanceof Number n ? n.longValue() : 0L; }
    private static String normalizeLinkType(String value) { String v = value == null || value.isBlank() ? "Other" : value.trim(); if (!List.of("Parent", "Child", "Requirement", "Epic / Feature", "Child Task", "Test Case", "Bug", "External URL", "Documentation", "Other").contains(v)) throw new IllegalArgumentException("Invalid related link type."); return v; }
    private static boolean matchesWorkItem(String query, String... values) { if (query.isBlank()) return true; for (String value : values) if (safe(value).toLowerCase().contains(query)) return true; return false; }
    private static String validateTarget(StoreData data, Story source, String value, String type) {
        requireText(value, "Work item ID or URL");
        if ("External URL".equals(type) || value.trim().startsWith("http://") || value.trim().startsWith("https://")) return validateUrl(value);
        String target = value.trim();
        if (target.equalsIgnoreCase(source.id())) throw new IllegalArgumentException("A work item cannot link to itself.");
        if ("Test Case".equals(type)) return target;
        Story targetStory = data.stories.stream().filter(s -> s.id().equalsIgnoreCase(target)).findFirst().orElse(null);
        Epic targetEpic = data.epics.stream().filter(e -> e.id().equalsIgnoreCase(target) || e.key().equalsIgnoreCase(target)).findFirst().orElse(null);
        if (targetStory == null && targetEpic == null) throw new IllegalArgumentException("Linked work item was not found: " + target);
        String targetProject = targetStory != null ? targetStory.projectId() : targetEpic.projectId();
        if (!source.projectId().equals(targetProject)) throw new IllegalArgumentException("Linked work item must belong to the same project.");
        if (("Parent".equals(type) || "Child".equals(type) || "Child Task".equals(type)) && targetStory == null) {
            if ("Parent".equals(type) || "Child".equals(type) || "Child Task".equals(type)) throw new IllegalArgumentException("Parent/child links must target a user story.");
        }
        if (targetStory != null && ("Parent".equals(type) || "Child".equals(type) || "Child Task".equals(type))) {
            String parent = "Parent".equals(type) ? targetStory.id() : source.id();
            String child = "Parent".equals(type) ? source.id() : targetStory.id();
            if (wouldCycle(data, parent, child)) throw new IllegalArgumentException("This link would create a circular parent-child relationship.");
        }
        return target;
    }
    private static boolean wouldCycle(StoreData data, String parent, String child) {
        if (parent.equals(child)) return true;
        List<String> pending = new ArrayList<>(); pending.add(child);
        java.util.Set<String> visited = new java.util.HashSet<>();
        while (!pending.isEmpty()) {
            String current = pending.remove(pending.size() - 1);
            if (!visited.add(current)) continue;
            if (parent.equals(current)) return true;
            for (RelatedLink link : data.relatedLinks) if (("Child".equals(link.type()) || "Child Task".equals(link.type())) && link.storyId().equals(current)) pending.add(link.url());
        }
        return false;
    }
    private static String validateUrl(String value) {
        requireText(value, "URL");
        if (value.matches("[A-Za-z][A-Za-z0-9_-]{1,30}-[0-9]+")) return value.trim().toUpperCase();
        try { URI uri = URI.create(value.trim()); if (!("http".equalsIgnoreCase(uri.getScheme()) || "https".equalsIgnoreCase(uri.getScheme())) || uri.getHost() == null || uri.getUserInfo() != null) throw new IllegalArgumentException("Only absolute HTTP/HTTPS URLs are allowed."); return uri.toASCIIString(); }
        catch (IllegalArgumentException e) { throw new IllegalArgumentException("Only absolute HTTP/HTTPS URLs are allowed."); }
    }

    private static final class StoreData {
        private final Map<String, Object> root;
        private final List<Project> projects;
        private final List<Epic> epics;
        private final List<Story> stories;
        private final List<Sprint> sprints;
        private final List<TestCaseLink> links;
        private final Map<String, Creator> creators;
        private final List<Attachment> attachments;
        private final List<RelatedLink> relatedLinks;
        private final List<Activity> activity;
        private StoreData(Map<String, Object> root, List<Project> projects, List<Epic> epics, List<Story> stories, List<Sprint> sprints, List<TestCaseLink> links, Map<String, Creator> creators, List<Attachment> attachments, List<RelatedLink> relatedLinks, List<Activity> activity) { this.root = root; this.projects = projects; this.epics = epics; this.stories = stories; this.sprints = sprints; this.links = links; this.creators = creators; this.attachments = attachments; this.relatedLinks = relatedLinks; this.activity = activity; }
    }
}
