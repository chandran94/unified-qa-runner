package com.example.unifiedqarunner;

import java.sql.Connection;
import java.time.Instant;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;

final class ConnectionTestService implements AutoCloseable {
    record TestState(String id, String connectionId, String status, String startedAt, String completedAt, String error) { }
    private final DatabaseConnectionRepository repository;
    private final DatabaseCredentialProtector protector;
    private final JdbcConnectionFactory factory;
    private final ExecutorService executor = Executors.newFixedThreadPool(2, r -> { Thread t=new Thread(r,"qa-db-test"); t.setDaemon(true); return t; });
    private final Map<String, TestState> states = new ConcurrentHashMap<>();
    private final Map<String, Future<?>> futures = new ConcurrentHashMap<>();

    ConnectionTestService(DatabaseConnectionRepository repository, DatabaseCredentialProtector protector, JdbcConnectionFactory factory) { this.repository=repository;this.protector=protector;this.factory=factory; }
    TestState submit(String connectionId) {
        DatabaseConnectionRepository.ConnectionConfig c=repository.find(connectionId); String id=UUID.randomUUID().toString(); TestState queued=new TestState(id,connectionId,"QUEUED","","","");states.put(id,queued);
        futures.put(id,executor.submit(() -> {String start=Instant.now().toString();states.put(id,new TestState(id,connectionId,"RUNNING",start,"",""));try(Connection ignored=factory.open(c,protector.decrypt(c.usernameEncrypted()),protector.decrypt(c.passwordEncrypted()))){repository.status(connectionId,"CONNECTED","");states.put(id,new TestState(id,connectionId,"PASSED",start,Instant.now().toString(),""));}catch(Exception e){String message=safe(e);try{repository.status(connectionId,"FAILED",message);}catch(Exception ignored){}states.put(id,new TestState(id,connectionId,"FAILED",start,Instant.now().toString(),message));}finally{futures.remove(id);}}));
        return queued;
    }
    TestState get(String id){return states.getOrDefault(id,new TestState(id,"","NOT_FOUND","","","Connection test not found."));}
    String connectionId(String id){return states.containsKey(id)?states.get(id).connectionId():"";}
    TestState cancel(String id){Future<?> f=futures.remove(id);if(f!=null){f.cancel(true);TestState old=states.get(id);TestState cancelled=new TestState(id,old.connectionId(),"CANCELLED",old.startedAt(),Instant.now().toString(),"Cancelled by user.");states.put(id,cancelled);return cancelled;}return get(id);}
    private String safe(Exception e){String m=e.getMessage();if(m==null||m.isBlank())return "Connection failed.";return m.replaceAll("(?i)(password|pwd)=[^;\\s]+","$1=***").replaceAll("(?i)(user|username)=[^;\\s]+","$1=***");}
    @Override public void close(){for(Future<?> f:futures.values())f.cancel(true);executor.shutdownNow();}
}
