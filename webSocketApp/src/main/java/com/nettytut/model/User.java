package com.nettytut.model;

import io.netty.channel.Channel;

public class User {
    protected final String login;
    protected final String password;

    public User(final String login, final String password) {
        this.login = login;
        this.password = password;
    }

    public String getLogin() {
        return login;
    }

    public String getPassword() {
        return password;
    }

}
