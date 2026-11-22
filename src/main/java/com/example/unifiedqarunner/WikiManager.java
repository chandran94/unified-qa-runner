package com.example.unifiedqarunner;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.time.Instant;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

/** Persistent Documentation/Wiki store using the application's existing JSON persistence. */
final class WikiManager {
    static final String DRAFT = "DRAFT";
    static final String PUBLISHED = "PUBLISHED";
    static final String ARCHIVED = "ARCHIVED";
    private static final int MAX_PARENT_DEPTH = 50;
    private final Path configPath;

    record Space(String id, String name, String key, String description, String createdBy,
                 String createdAt, String updatedAt, String status) { }
    record Page(String id, String spaceId, String parentPageId, String title, String content,
                String status, String createdBy, String updatedBy, String createdAt,
                String updatedAt, int version) { }

    WikiManager(Path projectRoot) { this.configPath = projectRoot.resolve("qa-runner.json").toAbsolutePath().normalize(); StorageWriteCoordinator.registerPersistentWriter(configPath.getParent(), "wiki"); }

    synchronized List<Space> listSpaces(String search) {
        String query = search == null ? "" : search.trim().toLowerCase();
        return readSpaces().stream().filter(space -> query.isBlank() || (space.name() + " " + space.key() + " " + space.description()).toLowerCase().contains(query)).toList();
    }
    synchronized Space getSpace(String id) { return readSpaces().stream().filter(space -> space.id().equals(id)).findFirst().orElseThrow(() -> new IllegalArgumentException("Wiki space not found.")); }
    synchronized Space createSpace(String name, String key, String description, String actor) throws IOException {
        String normalizedKey = normalizeKey(key); if (name == null || name.trim().isBlank()) throw new IllegalArgumentException("Space name is required.");
        List<Space> spaces = readSpaces(); if (spaces.stream().anyMatch(space -> space.key().equalsIgnoreCase(normalizedKey))) throw new IllegalArgumentException("Space key already exists.");
        String now = Instant.now().toString(); Space space = new Space(UUID.randomUUID().toString(), name.trim(), normalizedKey, safe(description), actor, now, now, "ACTIVE");
        List<Map<String, Object>> raw = readList("wikiSpaces"); raw.add(spaceMap(space)); writeList("wikiSpaces", raw); return space;
    }
    synchronized Space updateSpace(String id, Map<String, Object> values) throws IOException {
        Space current = getSpace(id); String name = required(values, "name"); String key = normalizeKey(stringValue(values, "key", current.key()));
        List<Space> spaces = readSpaces(); if (spaces.stream().anyMatch(space -> !space.id().equals(id) && space.key().equalsIgnoreCase(key))) throw new IllegalArgumentException("Space key already exists.");
        Space updated = new Space(id, name, key, safe(stringValue(values, "description", current.description())), current.createdBy(), current.createdAt(), Instant.now().toString(), current.status());
        replace("wikiSpaces", id, spaceMap(updated)); return updated;
    }
    synchronized Space setSpaceStatus(String id, String status) throws IOException { Space current = getSpace(id); if (!Set.of("ACTIVE", ARCHIVED).contains(status)) throw new IllegalArgumentException("Unsupported space status."); Space updated = new Space(current.id(), current.name(), current.key(), current.description(), current.createdBy(), current.createdAt(), Instant.now().toString(), status); replace("wikiSpaces", id, spaceMap(updated)); return updated; }

    synchronized List<Page> listPages(String spaceId, String search, boolean includeArchived) {
        getSpace(spaceId); String query = search == null ? "" : search.trim().toLowerCase();
        return readPages().stream().filter(page -> page.spaceId().equals(spaceId)).filter(page -> includeArchived || !ARCHIVED.equals(page.status())).filter(page -> query.isBlank() || (page.title() + " " + page.content()).toLowerCase().contains(query)).toList();
    }
    synchronized Page getPage(String id) { return readPages().stream().filter(page -> page.id().equals(id)).findFirst().orElseThrow(() -> new IllegalArgumentException("Wiki page not found.")); }
    synchronized Page createPage(Map<String, Object> values, String actor) throws IOException {
        String spaceId = required(values, "spaceId"); getSpace(spaceId); String parent = stringValue(values, "parentPageId", "");
        List<Page> pages = readPages(); validateParent(spaceId, parent, ""); String now = Instant.now().toString();
        Page page = new Page(UUID.randomUUID().toString(), spaceId, parent, required(values, "title"), sanitize(stringValue(values, "content", "")), DRAFT, actor, actor, now, now, 1);
        List<Map<String, Object>> raw = readList("wikiPages"); raw.add(pageMap(page)); writeList("wikiPages", raw); return page;
    }
    synchronized Page updatePage(String id, Map<String, Object> values, String actor) throws IOException {
        Page current = getPage(id); Object expected = values.get("version");
        if (expected != null && Integer.parseInt(String.valueOf(expected)) != current.version()) throw new IllegalStateException("This page changed since it was loaded. Refresh before saving.");
        String spaceId = stringValue(values, "spaceId", current.spaceId()); getSpace(spaceId); String parent = stringValue(values, "parentPageId", current.parentPageId()); validateParent(spaceId, parent, id);
        String status = stringValue(values, "status", current.status()); if (!Set.of(DRAFT, PUBLISHED, ARCHIVED).contains(status)) throw new IllegalArgumentException("Unsupported page status.");
        Page updated = new Page(id, spaceId, parent, required(values, "title"), sanitize(stringValue(values, "content", current.content())), status, current.createdBy(), actor, current.createdAt(), Instant.now().toString(), current.version() + 1);
        replace("wikiPages", id, pageMap(updated)); return updated;
    }
    synchronized Page setPageStatus(String id, String status, String actor, int expectedVersion) throws IOException { Map<String, Object> values = new LinkedHashMap<>(); values.put("title", getPage(id).title()); values.put("status", status); values.put("version", expectedVersion); return updatePage(id, values, actor); }

    static String sanitize(String content) {
        if (content == null) return "";
        return content.replaceAll("(?is)<(script|style|iframe|object|embed)[^>]*>.*?</\\1>", "")
                .replaceAll("(?i)\\s+on[a-z]+\\s*=\\s*(\\\"[^\\\"]*\\\"|'[^']*'|[^\\s>]+)", "")
                .replaceAll("(?i)(href|src)\\s*=\\s*(\\\"|')\\s*javascript:[^\\\"']*(\\\"|')", "$1=\\\"#\\\"");
    }

    private void validateParent(String spaceId, String parentId, String movingId) {
        if (parentId == null || parentId.isBlank()) return;
        List<Page> pages = readPages(); Page parent = pages.stream().filter(page -> page.id().equals(parentId)).findFirst().orElseThrow(() -> new IllegalArgumentException("Parent page not found."));
        if (!parent.spaceId().equals(spaceId) || parent.id().equals(movingId)) throw new IllegalArgumentException("Invalid parent page.");
        String cursor = parent.id(); for (int depth = 0; depth < MAX_PARENT_DEPTH; depth++) { String currentId = cursor; Page current = pages.stream().filter(page -> page.id().equals(currentId)).findFirst().orElse(null); if (current == null || current.parentPageId().isBlank()) return; if (current.parentPageId().equals(movingId)) throw new IllegalArgumentException("A page cannot be placed under its descendant."); cursor = current.parentPageId(); }
        throw new IllegalArgumentException("Page hierarchy exceeds the safe maximum depth.");
    }
    private String required(Map<String, Object> values, String key) { String value = stringValue(values, key, "").trim(); if (value.isBlank()) throw new IllegalArgumentException(key + " is required."); return value; }
    private String stringValue(Map<String, Object> values, String key, String fallback) { Object value = values.get(key); return value == null ? fallback : String.valueOf(value); }
    private String normalizeKey(String key) { String value = key == null ? "" : key.trim().toUpperCase(); if (!value.matches("[A-Z0-9][A-Z0-9_-]{1,19}")) throw new IllegalArgumentException("Space key must be 2-20 letters, numbers, _ or -."); return value; }
    private String safe(String value) { return value == null ? "" : value.trim(); }

    private List<Space> readSpaces() { return readList("wikiSpaces").stream().map(this::space).toList(); }
    private List<Page> readPages() { return readList("wikiPages").stream().map(this::page).toList(); }
    private Space space(Map<String, Object> map) { return new Space(str(map,"id"),str(map,"name"),str(map,"key"),str(map,"description"),str(map,"createdBy"),str(map,"createdAt"),str(map,"updatedAt"),str(map,"status")); }
    private Page page(Map<String, Object> map) { return new Page(str(map,"id"),str(map,"spaceId"),str(map,"parentPageId"),str(map,"title"),str(map,"content"),str(map,"status"),str(map,"createdBy"),str(map,"updatedBy"),str(map,"createdAt"),str(map,"updatedAt"),Integer.parseInt(String.valueOf(map.getOrDefault("version",1)))); }
    private String str(Map<String,Object> map,String key){return String.valueOf(map.getOrDefault(key,""));}
    private Map<String,Object> spaceMap(Space s){Map<String,Object> m=new LinkedHashMap<>();m.put("id",s.id());m.put("name",s.name());m.put("key",s.key());m.put("description",s.description());m.put("createdBy",s.createdBy());m.put("createdAt",s.createdAt());m.put("updatedAt",s.updatedAt());m.put("status",s.status());return m;}
    private Map<String,Object> pageMap(Page p){Map<String,Object> m=new LinkedHashMap<>();m.put("id",p.id());m.put("spaceId",p.spaceId());m.put("parentPageId",p.parentPageId());m.put("title",p.title());m.put("content",p.content());m.put("status",p.status());m.put("createdBy",p.createdBy());m.put("updatedBy",p.updatedBy());m.put("createdAt",p.createdAt());m.put("updatedAt",p.updatedAt());m.put("version",p.version());return m;}

    @SuppressWarnings("unchecked") private List<Map<String,Object>> readList(String key){try{if(!Files.exists(configPath))return new ArrayList<>();Object parsed=JsonParser.parse(Files.readString(configPath));if(!(parsed instanceof Map<?,?> root)||!(root.get(key) instanceof List<?> list))return new ArrayList<>();List<Map<String,Object>> out=new ArrayList<>();for(Object item:list)if(item instanceof Map<?,?> map)out.add(new LinkedHashMap<>((Map<String,Object>)map));return out;}catch(Exception e){throw new IllegalStateException("The Wiki store could not be read.",e);}}
    @SuppressWarnings("unchecked") private void writeList(String key,List<Map<String,Object>> values)throws IOException{StorageWriteCoordinator.coordinatedWrite(configPath.getParent(),"wiki",()->{Map<String,Object> root;if(Files.exists(configPath)){Object parsed=JsonParser.parse(Files.readString(configPath));root=parsed instanceof Map<?,?> map?new LinkedHashMap<>((Map<String,Object>)map):new LinkedHashMap<>();}else root=new LinkedHashMap<>();root.put("wikiSchemaVersion",1);root.put(key,values);Path tmp=configPath.resolveSibling(configPath.getFileName()+".wiki.tmp");Files.writeString(tmp,CiCdConfig.toJson(root));try{Files.move(tmp,configPath,StandardCopyOption.REPLACE_EXISTING,StandardCopyOption.ATOMIC_MOVE);}catch(java.nio.file.AtomicMoveNotSupportedException ignored){Files.move(tmp,configPath,StandardCopyOption.REPLACE_EXISTING);}});}
    private void replace(String key,String id,Map<String,Object> value)throws IOException{List<Map<String,Object>> raw=readList(key);raw.removeIf(item->id.equals(String.valueOf(item.get("id"))));raw.add(value);writeList(key,raw);}
}
