package com.bereket.secure_api.user;

public class UserResponse {

    private Long id;
    private String username;

    public UserResponse(Long id, String username) {
        this.id = id;
        this.username = username;
    }

    public Long getId() {
        return id;
    }

    public String getUsername() {
        return username;
    }
}