package com.example.unifiedqarunner;

import java.io.Console;
import java.nio.file.Path;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

/** Explicit local operator bootstrap; never invoked by normal application startup. */
public final class AuthBootstrap {
    private AuthBootstrap() { }

    public static void main(String[] args) throws Exception {
        if (args.length != 2 || !"--confirm-bootstrap".equals(args[0])) {
            System.err.println("Usage: AuthBootstrap --confirm-bootstrap <project-root>");
            return;
        }
        Console console = System.console();
        if (console == null) throw new IllegalStateException("Bootstrap requires an interactive local console.");
        Path root = Path.of(args[1]).toAbsolutePath().normalize();
        AuthUserDirectory users = new AuthUserDirectory(root);
        TeamMemberManager members = new TeamMemberManager(root);
        if (!users.list().isEmpty() || members.list().stream().anyMatch(member -> "Admin".equals(member.role()))) {
            throw new IllegalStateException("Bootstrap is already initialized or an Admin membership exists.");
        }
        String username = console.readLine("Username: ");
        String email = console.readLine("Email: ");
        String name = console.readLine("Display name: ");
        char[] password = console.readPassword("Password (12+ characters): ");
        char[] confirmation = console.readPassword("Repeat password: ");
        if (!java.util.Arrays.equals(password, confirmation)) throw new IllegalArgumentException("Passwords do not match.");
        AuthUserDirectory.User user = users.create(username, email, password, false);
        Map<String, Object> values = new LinkedHashMap<>();
        values.put("name", name); values.put("email", email); values.put("username", username);
        values.put("registeredUserId", user.id()); values.put("role", "Admin"); values.put("projectIds", List.of());
        TeamRoleManager roles = new TeamRoleManager(root);
        members.create(values, Set.of(), roles.list());
        roles.recordAudit("AUTH_BOOTSTRAP_COMPLETED", user.id(), "operator", "Local operator");
        System.out.println("Bootstrap completed for the explicitly approved local operator. Email verification remains required before production use.");
    }
}
