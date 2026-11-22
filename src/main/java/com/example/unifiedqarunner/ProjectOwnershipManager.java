package com.example.unifiedqarunner;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.time.Instant;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

/**
 * The authoritative ownership index for resources that predate project scoping.
 * It is deliberately additive: legacy records are never rewritten or deleted.
 * Records which cannot be derived from a trustworthy relationship remain
 * UNRESOLVED and must not be exposed to ordinary project users.
 */
final class ProjectOwnershipManager {
    static final String RESOLVED = "RESOLVED";
    static final String UNRESOLVED = "UNRESOLVED";
    private static final String KEY = "projectOwnership";
    private final Path configPath;

    record Ownership(String id, String resourceType, String resourceId, String projectId,
                     String status, String source, String createdAt, String updatedAt) { }

    ProjectOwnershipManager(Path projectRoot) {
        this.configPath = projectRoot.resolve("qa-runner.json").toAbsolutePath().normalize();
        StorageWriteCoordinator.registerPersistentWriter(configPath.getParent(), "project-ownership");
    }

    synchronized List<Ownership> list() {
        return read().stream().map(this::fromMap).toList();
    }

    synchronized List<Ownership> unresolved() {
        return list().stream().filter(item -> UNRESOLVED.equals(item.status())).toList();
    }

    synchronized Ownership find(String resourceType, String resourceId) {
        return list().stream().filter(item -> resourceType.equals(item.resourceType())
                && resourceId.equals(item.resourceId())).findFirst().orElse(null);
    }

    synchronized String projectId(String resourceType, String resourceId) {
        Ownership item = find(resourceType, resourceId);
        return item == null || !RESOLVED.equals(item.status()) ? "" : item.projectId();
    }

    synchronized Ownership assign(String resourceType, String resourceId, String projectId,
                                  String source, Set<String> validProjects) throws IOException {
        if (blank(resourceType) || blank(resourceId) || blank(projectId)) {
            throw new IllegalArgumentException("Resource type, resource ID and project ID are required.");
        }
        if (!validProjects.contains(projectId)) throw new IllegalArgumentException("Project does not exist.");
        Ownership existing = find(resourceType, resourceId);
        if (existing != null && RESOLVED.equals(existing.status()) && !projectId.equals(existing.projectId())) {
            throw new IllegalStateException("Resource ownership is immutable once established.");
        }
        String now = Instant.now().toString();
        Ownership result = existing == null
                ? new Ownership(UUID.randomUUID().toString(), resourceType, resourceId, projectId, RESOLVED,
                blank(source) ? "ADMIN_MIGRATION" : source, now, now)
                : new Ownership(existing.id(), resourceType, resourceId, projectId, RESOLVED,
                blank(source) ? existing.source() : source, existing.createdAt(), now);
        List<Map<String, Object>> values = read();
        values.removeIf(item -> resourceType.equals(str(item, "resourceType")) && resourceId.equals(str(item, "resourceId")));
        values.add(toMap(result));
        write(values);
        return result;
    }

    synchronized Ownership quarantine(String resourceType, String resourceId, String source) throws IOException {
        if (blank(resourceType) || blank(resourceId)) throw new IllegalArgumentException("Resource type and ID are required.");
        Ownership existing = find(resourceType, resourceId);
        if (existing != null) return existing;
        String now = Instant.now().toString();
        Ownership result = new Ownership(UUID.randomUUID().toString(), resourceType, resourceId, "", UNRESOLVED,
                blank(source) ? "LEGACY_UNRESOLVED" : source, now, now);
        List<Map<String, Object>> values = read();
        values.add(toMap(result));
        write(values);
        return result;
    }

    /** Idempotently derives ownership only from explicit, existing relationships. */
    synchronized int migrate(UserStoryStore storyStore, List<Map<String, Object>> defects) throws IOException {
        Set<String> projectIds = storyStore.listProjects(true).stream().map(UserStoryStore.Project::id).collect(java.util.stream.Collectors.toSet());
        Map<String, String> storyProjects = new LinkedHashMap<>();
        for (UserStoryStore.Story story : storyStore.listStories(null, null, null, 0, 100000, true)) {
            if (projectIds.contains(story.projectId())) storyProjects.put(story.id(), story.projectId());
        }
        Map<String, String> testCaseProjects = new LinkedHashMap<>();
        Map<String, Object> root = readRoot();
        for (Map<String, Object> item : listOfMaps(root.get("testCases"))) {
            String id = str(item, "id");
            String storyId = str(item, "storyId");
            String project = storyProjects.get(storyId);
            if (blank(project)) {
                for (Map<String, Object> link : listOfMaps(root.get("userStoryTestCaseLinks"))) {
                    if (id.equals(str(link, "testCaseId")) && storyProjects.containsKey(str(link, "storyId"))) {
                        project = storyProjects.get(str(link, "storyId")); break;
                    }
                }
            }
            if (!blank(id)) {
                upsertDerived("TEST_CASE", id, project, blank(project) ? "LEGACY_UNRESOLVED" : "USER_STORY_RELATIONSHIP");
                if (!blank(project)) testCaseProjects.put(id, project);
            }
        }
        for (Map<String, Object> defect : defects) {
            String id = str(defect, "id"); String project = str(defect, "projectId");
            if (!projectIds.contains(project)) project = "";
            upsertDerived("DEFECT", id, project, blank(project) ? "LEGACY_UNRESOLVED" : "DEFECT_PROJECT_ID");
        }
        for (Map<String, Object> mapping : listOfMaps(root.get("automationMappings"))) {
            String id = str(mapping, "id"); String project = testCaseProjects.get(str(mapping, "testCaseId"));
            upsertDerived("AUTOMATION_MAPPING", id, project, blank(project) ? "LEGACY_UNRESOLVED" : "TEST_CASE_RELATIONSHIP");
        }
        for (Map<String, Object> attachment : listOfMaps(root.get("userStoryAttachments"))) {
            String id = str(attachment, "id");
            String project = storyProjects.get(str(attachment, "storyId"));
            upsertDerived("ATTACHMENT", id, project, blank(project) ? "LEGACY_UNRESOLVED" : "USER_STORY_RELATIONSHIP");
        }
        for (Map<String, Object> execution : listOfMaps(root.get("automationExecutions"))) {
            String id = str(execution, "executionId"); String project = testCaseProjects.get(str(execution, "testCaseId"));
            upsertDerived("AUTOMATION_EXECUTION", id, project, blank(project) ? "LEGACY_UNRESOLVED" : "TEST_CASE_RELATIONSHIP");
        }
        // Wiki, runner artifacts and legacy CI/CD data have no trustworthy project key today.
        for (Map<String, Object> space : listOfMaps(root.get("wikiSpaces"))) upsertDerived("WIKI_SPACE", str(space, "id"), "", "LEGACY_UNRESOLVED");
        for (Map<String, Object> page : listOfMaps(root.get("wikiPages"))) upsertDerived("WIKI_PAGE", str(page, "id"), "", "LEGACY_UNRESOLVED");
        for (Map<String, Object> path : listOfMaps(root.get("sharedPaths"))) upsertDerived("SHARED_PATH", str(path, "name"), "", "LEGACY_UNRESOLVED");
        for (Map<String, Object> imported : listOfMaps(root.get("imports"))) upsertDerived("REMOTE_IMPORT", str(imported, "id"), "", "LEGACY_UNRESOLVED");
        return unresolved().size();
    }

    private void upsertDerived(String type, String id, String project, String source) throws IOException {
        if (blank(id) || find(type, id) != null) return;
        String now = Instant.now().toString();
        List<Map<String, Object>> values = read();
        values.add(toMap(new Ownership(UUID.randomUUID().toString(), type, id, project,
                blank(project) ? UNRESOLVED : RESOLVED, source, now, now)));
        write(values);
    }

    private Ownership fromMap(Map<String, Object> item) { return new Ownership(str(item,"id"),str(item,"resourceType"),str(item,"resourceId"),str(item,"projectId"),str(item,"status"),str(item,"source"),str(item,"createdAt"),str(item,"updatedAt")); }
    private Map<String,Object> toMap(Ownership item) { Map<String,Object> map=new LinkedHashMap<>(); map.put("id",item.id()); map.put("resourceType",item.resourceType()); map.put("resourceId",item.resourceId()); map.put("projectId",item.projectId()); map.put("status",item.status()); map.put("source",item.source()); map.put("createdAt",item.createdAt()); map.put("updatedAt",item.updatedAt()); return map; }
    private static String str(Map<String,Object> map,String key){Object value=map.get(key);return value==null?"":String.valueOf(value);}
    private static boolean blank(String value){return value==null||value.isBlank();}
    private Map<String,Object> readRoot(){try{if(!Files.exists(configPath))return new LinkedHashMap<>();Object parsed=JsonParser.parse(Files.readString(configPath));if(!(parsed instanceof Map<?,?> map))return new LinkedHashMap<>();Map<String,Object> copy=new LinkedHashMap<>();for(Map.Entry<?,?> entry:map.entrySet())copy.put(String.valueOf(entry.getKey()),entry.getValue());return copy;}catch(Exception e){throw new IllegalStateException("Project ownership store could not be read.",e);}}
    private List<Map<String,Object>> read(){Object raw=readRoot().get(KEY);List<Map<String,Object>> result=new ArrayList<>();for(Map<String,Object> item:listOfMaps(raw)){Map<String,Object> copy=new LinkedHashMap<>();copy.putAll(item);result.add(copy);}return result;}
    @SuppressWarnings("unchecked") private static List<Map<String,Object>> listOfMaps(Object raw){List<Map<String,Object>> result=new ArrayList<>();if(raw instanceof List<?> list)for(Object item:list)if(item instanceof Map<?,?> map){Map<String,Object> copy=new LinkedHashMap<>();for(Map.Entry<?,?> entry:map.entrySet())copy.put(String.valueOf(entry.getKey()),entry.getValue());result.add(copy);}return result;}
    @SuppressWarnings("unchecked") private void write(List<Map<String,Object>> values)throws IOException{StorageWriteCoordinator.coordinatedWrite(configPath.getParent(),"project-ownership",()->{Map<String,Object> root=readRoot();root.put("projectOwnershipSchemaVersion",1);root.put(KEY,values);Path tmp=configPath.resolveSibling(configPath.getFileName()+".ownership.tmp");Files.writeString(tmp,CiCdConfig.toJson(root));try{Files.move(tmp,configPath,StandardCopyOption.REPLACE_EXISTING,StandardCopyOption.ATOMIC_MOVE);}catch(java.nio.file.AtomicMoveNotSupportedException ignored){Files.move(tmp,configPath,StandardCopyOption.REPLACE_EXISTING);}});}
}
