package com.example.unifiedqarunner;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.time.Instant;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.UUID;

/** Compatibility-store repository. Password and username fields are encrypted before they reach qa-runner.json. */
final class DatabaseConnectionRepository {
    private static final String KEY = "databaseConnections";
    private final Path configPath;

    record ConnectionConfig(String id, String organizationId, String name, String type, String host, int port, String databaseName,
                            String usernameEncrypted, String passwordEncrypted, String environment, String tlsMode,
                            int connectionTimeoutSeconds, boolean readOnly, String status, String lastTestAt,
                            String lastError, String createdAt, String updatedAt) { }

    DatabaseConnectionRepository(Path root) { configPath = root.resolve("qa-runner.json").toAbsolutePath().normalize(); }

    synchronized List<ConnectionConfig> list() { return read().stream().map(this::fromMap).toList(); }
    synchronized List<ConnectionConfig> listForOrganization(String organizationId) {
        return list().stream().filter(item -> organizationId != null && organizationId.equals(item.organizationId())).toList();
    }
    synchronized ConnectionConfig find(String id) { return list().stream().filter(item -> item.id().equals(id)).findFirst().orElseThrow(() -> new IllegalArgumentException("Database connection not found.")); }
    ConnectionConfig preview(Map<String, Object> values) { return build("preview", String.valueOf(values.getOrDefault("organizationId", "")), values, "", "", "DISCONNECTED", "", "", "", ""); }

    synchronized ConnectionConfig create(Map<String, Object> values, String usernameEncrypted, String passwordEncrypted) throws IOException {
        List<ConnectionConfig> current = list();
        String name = required(values, "name");
        if (current.stream().anyMatch(item -> item.name().equalsIgnoreCase(name))) throw new IllegalArgumentException("A connection with this name already exists.");
        String now = Instant.now().toString();
        ConnectionConfig result = build(UUID.randomUUID().toString(), String.valueOf(values.getOrDefault("organizationId", "")), values, usernameEncrypted, passwordEncrypted, "DISCONNECTED", "", "", now, now);
        List<Map<String, Object>> raw = read(); raw.add(toMap(result)); write(raw); return result;
    }

    synchronized ConnectionConfig createForOrganization(String organizationId, Map<String, Object> values,
                                                         String usernameEncrypted, String passwordEncrypted) throws IOException {
        Map<String, Object> scoped = new LinkedHashMap<>(values);
        scoped.put("organizationId", organizationId);
        return create(scoped, usernameEncrypted, passwordEncrypted);
    }

    synchronized ConnectionConfig update(String id, Map<String, Object> values, String usernameEncrypted, String passwordEncrypted) throws IOException {
        ConnectionConfig existing = find(id);
        String name = values.containsKey("name") ? required(values, "name") : existing.name();
        if (list().stream().anyMatch(item -> !item.id().equals(id) && item.name().equalsIgnoreCase(name))) throw new IllegalArgumentException("A connection with this name already exists.");
        Map<String, Object> merged = new LinkedHashMap<>(); merged.put("name", name); merged.put("type", values.getOrDefault("type", existing.type())); merged.put("host", values.getOrDefault("host", existing.host())); merged.put("port", values.getOrDefault("port", existing.port())); merged.put("databaseName", values.getOrDefault("databaseName", existing.databaseName())); merged.put("environment", values.getOrDefault("environment", existing.environment())); merged.put("tlsMode", values.getOrDefault("tlsMode", existing.tlsMode())); merged.put("connectionTimeoutSeconds", values.getOrDefault("connectionTimeoutSeconds", existing.connectionTimeoutSeconds())); merged.put("readOnly", values.getOrDefault("readOnly", existing.readOnly()));
        ConnectionConfig result = build(id, existing.organizationId(), merged, usernameEncrypted.isBlank() ? existing.usernameEncrypted() : usernameEncrypted, passwordEncrypted.isBlank() ? existing.passwordEncrypted() : passwordEncrypted, existing.status(), existing.lastTestAt(), existing.lastError(), existing.createdAt(), Instant.now().toString());
        List<Map<String, Object>> raw = read(); raw.removeIf(item -> id.equals(String.valueOf(item.get("id")))); raw.add(toMap(result)); write(raw); return result;
    }

    synchronized ConnectionConfig updateForOrganization(String organizationId, String id, Map<String, Object> values,
                                                         String usernameEncrypted, String passwordEncrypted) throws IOException {
        ConnectionConfig existing = find(id);
        if (!organizationId.equals(existing.organizationId())) throw new IllegalArgumentException("Database connection not found.");
        return update(id, values, usernameEncrypted, passwordEncrypted);
    }

    synchronized void delete(String id) throws IOException { find(id); List<Map<String, Object>> raw = read(); raw.removeIf(item -> id.equals(String.valueOf(item.get("id")))); write(raw); }
    synchronized ConnectionConfig status(String id, String status, String error) throws IOException { ConnectionConfig old=find(id); Map<String,Object> values=new LinkedHashMap<>(); values.put("name",old.name()); values.put("type",old.type()); values.put("host",old.host()); values.put("port",old.port()); values.put("databaseName",old.databaseName()); values.put("environment",old.environment()); values.put("tlsMode",old.tlsMode()); values.put("connectionTimeoutSeconds",old.connectionTimeoutSeconds()); values.put("readOnly",old.readOnly()); ConnectionConfig result=build(old.id(),old.organizationId(),values,old.usernameEncrypted(),old.passwordEncrypted(),status,Instant.now().toString(),error==null?"":error,old.createdAt(),Instant.now().toString()); List<Map<String,Object>> raw=read(); raw.removeIf(item->id.equals(String.valueOf(item.get("id")))); raw.add(toMap(result)); write(raw); return result; }

    private ConnectionConfig build(String id, String organizationId, Map<String,Object> v, String user, String password, String status, String lastTest, String error, String created, String updated) {
        String type=JdbcDriverRegistry.normalizeType(String.valueOf(v.getOrDefault("type", ""))); String environment=String.valueOf(v.getOrDefault("environment", "LOCAL")).trim().toUpperCase(Locale.ROOT); String tls=String.valueOf(v.getOrDefault("tlsMode", "VERIFY")).trim().toUpperCase(Locale.ROOT);
        return new ConnectionConfig(id, organizationId, required(v,"name"), type, required(v,"host"), integer(v,"port",0), required(v,"databaseName"), user, password, environment, tls, integer(v,"connectionTimeoutSeconds",5), bool(v,"readOnly",true), status,lastTest,error,created,updated);
    }
    private String required(Map<String,Object> v,String key){String s=String.valueOf(v.getOrDefault(key,"")).trim();if(s.isBlank())throw new IllegalArgumentException(key+" is required.");if(s.length()>160)throw new IllegalArgumentException(key+" is too long.");return s;}
    private int integer(Map<String,Object> v,String key,int fallback){try{return Integer.parseInt(String.valueOf(v.getOrDefault(key,fallback)));}catch(NumberFormatException e){throw new IllegalArgumentException(key+" must be a number.");}}
    private boolean bool(Map<String,Object> v,String key,boolean fallback){Object x=v.get(key);return x==null?fallback:Boolean.parseBoolean(String.valueOf(x));}
    @SuppressWarnings("unchecked") private List<Map<String,Object>> read(){try{if(!Files.exists(configPath))return new ArrayList<>();Object p=JsonParser.parse(Files.readString(configPath));if(!(p instanceof Map<?,?> root)||!(root.get(KEY) instanceof List<?> items))return new ArrayList<>();List<Map<String,Object>> r=new ArrayList<>();for(Object x:items)if(x instanceof Map<?,?> m)r.add(new LinkedHashMap<>((Map<String,Object>)m));return r;}catch(Exception e){throw new IllegalStateException("The database connection store could not be read.",e);}}
    @SuppressWarnings("unchecked") private void write(List<Map<String,Object>> values)throws IOException{Map<String,Object> root;if(Files.exists(configPath)){Object p=JsonParser.parse(Files.readString(configPath));root=p instanceof Map<?,?> m?new LinkedHashMap<>((Map<String,Object>)m):new LinkedHashMap<>();}else root=new LinkedHashMap<>();root.put(KEY,values);Path tmp=configPath.resolveSibling(configPath.getFileName()+".database.tmp");Files.writeString(tmp,CiCdConfig.toJson(root));try{Files.move(tmp,configPath,StandardCopyOption.REPLACE_EXISTING,StandardCopyOption.ATOMIC_MOVE);}catch(java.nio.file.AtomicMoveNotSupportedException e){Files.move(tmp,configPath,StandardCopyOption.REPLACE_EXISTING);}}
    private ConnectionConfig fromMap(Map<String,Object> m){return new ConnectionConfig(String.valueOf(m.getOrDefault("id","")),String.valueOf(m.getOrDefault("organizationId","")),String.valueOf(m.getOrDefault("name","")),String.valueOf(m.getOrDefault("type","")),String.valueOf(m.getOrDefault("host","")),integer(m,"port",0),String.valueOf(m.getOrDefault("databaseName","")),String.valueOf(m.getOrDefault("usernameEncrypted","")),String.valueOf(m.getOrDefault("passwordEncrypted","")),String.valueOf(m.getOrDefault("environment","LOCAL")),String.valueOf(m.getOrDefault("tlsMode","VERIFY")),integer(m,"connectionTimeoutSeconds",5),bool(m,"readOnly",true),String.valueOf(m.getOrDefault("status","DISCONNECTED")),String.valueOf(m.getOrDefault("lastTestAt","")),String.valueOf(m.getOrDefault("lastError","")),String.valueOf(m.getOrDefault("createdAt","")),String.valueOf(m.getOrDefault("updatedAt","")));}
    private Map<String,Object> toMap(ConnectionConfig c){Map<String,Object> m=new LinkedHashMap<>();m.put("id",c.id());m.put("organizationId",c.organizationId());m.put("name",c.name());m.put("type",c.type());m.put("host",c.host());m.put("port",c.port());m.put("databaseName",c.databaseName());m.put("usernameEncrypted",c.usernameEncrypted());m.put("passwordEncrypted",c.passwordEncrypted());m.put("environment",c.environment());m.put("tlsMode",c.tlsMode());m.put("connectionTimeoutSeconds",c.connectionTimeoutSeconds());m.put("readOnly",c.readOnly());m.put("status",c.status());m.put("lastTestAt",c.lastTestAt());m.put("lastError",c.lastError());m.put("createdAt",c.createdAt());m.put("updatedAt",c.updatedAt());return m;}
}
