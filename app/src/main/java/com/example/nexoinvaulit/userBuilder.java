package com.example.nexoinvaulit;


public class userBuilder {
    private String userId;
    private String email;
    private String uid;

    public userBuilder setUserId(String userId) {
        this.userId = userId;
        return this;
    }

    public userBuilder setEmail(String email) {
        this.email = email;
        return this;
    }

    public userBuilder setUid(String uid) {
        this.uid = uid;
        return this;
    }

    public User createUser() {
        return new User(userId, email);
    }
}