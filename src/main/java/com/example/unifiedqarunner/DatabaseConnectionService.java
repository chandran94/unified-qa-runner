package com.example.unifiedqarunner;

import java.io.IOException;
import java.sql.Connection;
import java.util.ArrayList;
import java.util.List;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

final class DatabaseConnectionService implements AutoCloseable {
    private final DatabaseConnectionRepository repository;
    private final DatabaseCredentialProtector protector = new DatabaseCredentialProtector();
    private final JdbcConnectionFactory factory = new JdbcConnectionFactory();
    private final ConnectionTestService tests;
    private final Map<String, Connection> active = new ConcurrentHashMap<>();
    DatabaseConnectionService(java.nio.file.Path root){repository=new DatabaseConnectionRepository(root);tests=new ConnectionTestService(repository,protector,factory);}
    Map<String,Object> list(String search,int page,int pageSize){return listForOrganization("", search, page, pageSize);}
    Map<String,Object> listForOrganization(String organizationId,String search,int page,int pageSize){List<DatabaseConnectionRepository.ConnectionConfig> all=repository.listForOrganization(organizationId);String q=search==null?"":search.trim().toLowerCase();List<Map<String,Object>> safe=new ArrayList<>();for(var c:all)if(q.isBlank()||c.name().toLowerCase().contains(q)||c.host().toLowerCase().contains(q)||c.type().toLowerCase().contains(q))safe.add(safe(c));int boundedSize=Math.max(1,Math.min(50,pageSize));int boundedPage=Math.max(0,page);int from=Math.min(boundedPage*boundedSize,safe.size());int to=Math.min(from+boundedSize,safe.size());return Map.of("items",safe.subList(from,to),"total",safe.size(),"page",boundedPage,"pageSize",boundedSize);}
    Map<String,Object> get(String id){return safe(repository.find(id));}
    Map<String,Object> create(Map<String,Object> values)throws IOException{requireKey();factory.validate(repository.preview(values));DatabaseConnectionRepository.ConnectionConfig c=repository.create(values,protector.encrypt(String.valueOf(values.getOrDefault("username",""))),protector.encrypt(String.valueOf(values.getOrDefault("password",""))));return safe(c);}
    Map<String,Object> createForOrganization(String organizationId,Map<String,Object> values)throws IOException{requireKey();factory.validate(repository.preview(values));DatabaseConnectionRepository.ConnectionConfig c=repository.createForOrganization(organizationId,values,protector.encrypt(String.valueOf(values.getOrDefault("username",""))),protector.encrypt(String.valueOf(values.getOrDefault("password",""))));return safe(c);}
    Map<String,Object> update(String id,Map<String,Object> values)throws IOException{requireKey();DatabaseConnectionRepository.ConnectionConfig current=repository.find(id);Map<String,Object> preview=new LinkedHashMap<>();preview.put("name",values.getOrDefault("name",current.name()));preview.put("type",values.getOrDefault("type",current.type()));preview.put("host",values.getOrDefault("host",current.host()));preview.put("port",values.getOrDefault("port",current.port()));preview.put("databaseName",values.getOrDefault("databaseName",current.databaseName()));preview.put("environment",values.getOrDefault("environment",current.environment()));preview.put("tlsMode",values.getOrDefault("tlsMode",current.tlsMode()));preview.put("connectionTimeoutSeconds",values.getOrDefault("connectionTimeoutSeconds",current.connectionTimeoutSeconds()));preview.put("readOnly",values.getOrDefault("readOnly",current.readOnly()));factory.validate(repository.preview(preview));String u=String.valueOf(values.getOrDefault("username","")),p=String.valueOf(values.getOrDefault("password",""));DatabaseConnectionRepository.ConnectionConfig c=repository.update(id,values,protector.encrypt(u),protector.encrypt(p));return safe(c);}
    Map<String,Object> updateForOrganization(String organizationId,String id,Map<String,Object> values)throws IOException{requireOwner(organizationId,id);return update(id,values);}
    void testDraft(String organizationId, Map<String, Object> values) {
        var config = repository.preview(values);
        String username = String.valueOf(values.getOrDefault("username", "")).trim();
        String password = String.valueOf(values.getOrDefault("password", ""));
        Object savedId = values.get("connectionId");
        if (savedId instanceof String id && !id.isBlank()) {
            requireOwner(organizationId, id);
            var saved = repository.find(id);
            boolean sameEndpoint = config.type().equals(saved.type()) && config.host().equals(saved.host())
                    && config.port() == saved.port() && config.databaseName().equals(saved.databaseName())
                    && config.tlsMode().equals(saved.tlsMode()) && config.environment().equals(saved.environment());
            if (sameEndpoint && (username.isBlank() || password.isEmpty())) {
                requireKey();
                if (username.isBlank()) username = protector.decrypt(saved.usernameEncrypted());
                if (password.isEmpty()) password = protector.decrypt(saved.passwordEncrypted());
            }
        }
        if (username.isBlank() || password.isEmpty()) {
            throw new IllegalArgumentException("Enter the database username and password to test these settings.");
        }
        try (Connection connection = factory.open(config, username, password)) {
            if (!connection.isValid(config.connectionTimeoutSeconds())) {
                throw new IllegalArgumentException("The database did not respond to the connection check.");
            }
        } catch (java.sql.SQLException error) {
            String state = error.getSQLState();
            String message = state != null && state.startsWith("28")
                    ? "Database authentication failed. Check the database username and password."
                    : "Could not connect. Check the host, port, database name, and TLS settings.";
            throw new IllegalArgumentException(message);
        }
    }

    Map<String, Object> browseForOrganization(String organizationId, String id, String schema, String table) throws Exception {
        requireOwner(organizationId, id);
        requireKey();
        var config = repository.find(id);
        if (!config.readOnly()) throw new IllegalArgumentException("Enable read-only mode for this connection before browsing.");
        try (Connection connection = factory.open(config, protector.decrypt(config.usernameEncrypted()),
                protector.decrypt(config.passwordEncrypted()))) {
            return DatabaseWorkspace.browse(connection, schema, table);
        } catch (java.sql.SQLException error) {
            throw new IllegalArgumentException("Could not browse the database. Check connectivity and the account's read permissions.");
        }
    }

    void delete(String id)throws Exception{disconnect(id);repository.delete(id);}
    Map<String,Object> connect(String id)throws Exception{requireKey();DatabaseConnectionRepository.ConnectionConfig c=repository.find(id);Connection connection=factory.open(c,protector.decrypt(c.usernameEncrypted()),protector.decrypt(c.passwordEncrypted()));Connection old=active.put(id,connection);if(old!=null)try{old.close();}catch(Exception ignored){}return safe(repository.status(id,"CONNECTED",""));}
    Map<String,Object> connectForOrganization(String organizationId,String id)throws Exception{requireOwner(organizationId,id);return connect(id);}
    Map<String,Object> disconnect(String id)throws Exception{Connection c=active.remove(id);if(c!=null)c.close();return safe(repository.status(id,"DISCONNECTED",""));}
    Map<String,Object> disconnectForOrganization(String organizationId,String id)throws Exception{requireOwner(organizationId,id);return disconnect(id);}
    ConnectionTestService.TestState submitTest(String id){requireKey();return tests.submit(id);}
    ConnectionTestService.TestState submitTestForOrganization(String organizationId,String id){requireOwner(organizationId,id);requireKey();return tests.submit(id);}
    ConnectionTestService.TestState testStatus(String id){return tests.get(id);}
    ConnectionTestService.TestState cancelTest(String id){return tests.cancel(id);}
    ConnectionTestService.TestState cancelTestForOrganization(String organizationId,String id){String connectionId=tests.connectionId(id);requireOwner(organizationId,connectionId);return tests.cancel(id);}
    String testConnectionId(String id){return tests.connectionId(id);}
    String connectionOrganizationId(String id){return repository.find(id).organizationId();}
    private void requireOwner(String organizationId,String id){if(organizationId==null||organizationId.isBlank()||id==null||id.isBlank()||!organizationId.equals(repository.find(id).organizationId()))throw new IllegalArgumentException("The database connection is not authorized for this organization.");}
    private void requireKey(){if(!protector.configured())throw new IllegalStateException("Database credential encryption key is not configured. Set QA_RUNNER_DB_ENCRYPTION_KEY before creating or testing connections.");}
    private Map<String,Object> safe(DatabaseConnectionRepository.ConnectionConfig c){Map<String,Object> m=new java.util.LinkedHashMap<>();m.put("id",c.id());m.put("name",c.name());m.put("type",c.type());m.put("host",c.host());m.put("port",c.port());m.put("databaseName",c.databaseName());m.put("username",c.usernameEncrypted().isBlank()?"":"configured");m.put("hasPassword",!c.passwordEncrypted().isBlank());m.put("environment",c.environment());m.put("tlsMode",c.tlsMode());m.put("connectionTimeoutSeconds",c.connectionTimeoutSeconds());m.put("readOnly",c.readOnly());m.put("status",c.status());m.put("lastTestAt",c.lastTestAt());m.put("lastError",c.lastError());m.put("createdAt",c.createdAt());m.put("updatedAt",c.updatedAt());return m;}
    @Override public void close(){for(Connection c:active.values())try{c.close();}catch(Exception ignored){}active.clear();tests.close();}
}
