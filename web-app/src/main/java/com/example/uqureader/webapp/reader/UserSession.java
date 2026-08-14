package com.example.uqureader.webapp.reader;

public final class UserSession {
    public final long userId;
    public final String username;
    public final String sessionToken;
    public final long expiresAtMs;

    public UserSession(long userId, String username, String sessionToken, long expiresAtMs) {
        this.userId = userId;
        this.username = username == null ? "" : username;
        this.sessionToken = sessionToken == null ? "" : sessionToken;
        this.expiresAtMs = expiresAtMs;
    }
}
