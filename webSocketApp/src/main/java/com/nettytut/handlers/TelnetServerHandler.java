package com.nettytut.handlers;


import com.nettytut.exceptions.InvalidChatChannelGroup;
import com.nettytut.exceptions.InvalidChatChannelName;
import com.nettytut.model.User;
import io.netty.channel.Channel;
import io.netty.channel.ChannelHandler.Sharable;
import io.netty.channel.ChannelHandlerContext;
import io.netty.channel.SimpleChannelInboundHandler;
import io.netty.channel.group.ChannelGroup;
import io.netty.util.AttributeKey;

import java.net.InetAddress;
import java.util.Date;
import java.util.HashMap;
import java.util.Map;
import java.util.concurrent.BlockingQueue;
import java.util.concurrent.LinkedBlockingQueue;

/**
 * Handles a server-side channel.
 */
@Sharable
public class TelnetServerHandler extends SimpleChannelInboundHandler<String> {

    private final Map<String, ChannelGroup> chatChannelGroup;
    private final Map<User, String> userChatChannelMap;
    private User user;

    private final static AttributeKey<User> USER_ATTRIBUTE_KEY = AttributeKey.valueOf("user");
    private final static Map<String, BlockingQueue<String>> lastMessages;
    private final static int QUANTITY_OF_SHOWING_MESSAGES = 10;
    private final static int GROUP_CAPACITY = 10;

    static {
        lastMessages = new HashMap<>();
        lastMessages.put("zepto", new LinkedBlockingQueue<>());
        lastMessages.put("test", new LinkedBlockingQueue<>());
    }

    public TelnetServerHandler(Map<String, ChannelGroup> chatChannelGroup, Map<User, String> userChatChannelMap) {
        this.chatChannelGroup = chatChannelGroup;
        this.userChatChannelMap = userChatChannelMap;
    }

    @Override
    public void channelActive(ChannelHandlerContext ctx) throws Exception {
        // Send greeting for a new connection.
        ctx.write("Welcome to " + InetAddress.getLocalHost().getHostName() + "!\r\n");
        ctx.write("It is " + new Date() + " now.\r\n");
        ctx.flush();
    }

    @Override
    public void channelRead0(ChannelHandlerContext ctx, String request) throws Exception {
        // Generate and write a response.
        boolean close = false;
        if (request.isEmpty()) {
            writeMessageFromContextHandler(ctx, "Please type something.\r\n");
        } else if ("bye".equals(request.toLowerCase())) {
            close = true;
        } else if ("channels".equals(request.toLowerCase())) {
            showAllChannels(ctx);
        } else if (request.startsWith("login")) {
            authorizeUser(ctx, request);
        } else if (request.startsWith("join")) {
            joinUserToChannel(request, ctx);
        } else if ("users".equals(request.toLowerCase())) {
            try {
                showAllUsersFromChannel(ctx);
            } catch (InvalidChatChannelName | InvalidChatChannelGroup e) {
                writeMessageFromContextHandler(ctx, e.getMessage());
            }
        } else {
            if(isUserLoggedIn()) {
                showMessage(ctx, request);
            } else {
                writeMessageFromContextHandler(ctx, "You're not sign in.\r\n");
            }
        }
        // Close the connection after sending 'Have a good day!'
        // if the client has sent 'bye'.
        if (close) ctx.close();
    }

    @Override
    public void channelReadComplete(ChannelHandlerContext ctx) {
        ctx.flush();
    }

    @Override
    public void exceptionCaught(ChannelHandlerContext ctx, Throwable cause) {
        cause.printStackTrace();
        ctx.close();
    }

    private void joinUserToChannel(String request, ChannelHandlerContext ctx) {
        if(!isUserLoggedIn()) {
            writeMessageFromContextHandler(ctx, "You're not logged in.");
            return;
        }
        if(isUserInGroup()) {
            writeMessageFromContextHandler(ctx, "You're already in chat channel " + getChatChannelNameForUser(user));
            return;
        }
        String[] params = request.split(" ");
        if (params.length != 2) return;

        String chatChannelName = params[1];

        if(isChatChannelGroupFull(chatChannelName)) {
            writeMessageFromContextHandler(ctx, "There is no place in channel " + chatChannelName);
            return;
        }

        if(!chatChannelGroup.containsKey(chatChannelName)) {
            writeMessageFromContextHandler(ctx, "There is no channels with name " + chatChannelName);
            return;
        }
        ChannelGroup channelGroup = chatChannelGroup.get(chatChannelName);
        if(!channelGroup.contains(ctx.channel())) {
            assignUserToChatChannel(ctx, chatChannelName);
        }
        printMessages(ctx, chatChannelName);
    }

    protected boolean isChatChannelGroupFull(String chatChannelName) {
        ChannelGroup chg = chatChannelGroup.get(chatChannelName);
        if(chg == null) return false;

        return (chg.size() >= GROUP_CAPACITY);


    }

    private boolean isUserInGroup() {
        String chatChannelName = getChatChannelNameForUser(user);
        return chatChannelName != null;
    }

    private void authorizeUser(ChannelHandlerContext ctx, String request) {
        String[] params = request.split(" ");
        if(params.length < 3) {
            writeMessageFromContextHandler(ctx, "Wrong login/password pair. \r\n");
            return;
        }
        String login = params[1];
        String password = params[2];

        User newUser = new User(login, password);
        ctx.channel().attr(USER_ATTRIBUTE_KEY).set(newUser);

        if(validateUser(newUser)) {
            if(newUser.equals(user)) {
                writeMessageFromContextHandler(ctx, "You're already signed in.\r\n");
            } else {
                user = newUser;
                writeMessageFromContextHandler(ctx, "You're successfully signed in." +
                        " Your login is " + login + " \r\n");
            }
            addUserInChatChannel(ctx, newUser);
        } else {
            userChatChannelMap.put(newUser, null);
            user = newUser;
            writeMessageFromContextHandler(ctx, "You're successfully signed up." +
                    " Your login is " + login + " \r\n");
        }
    }

    /**
     * Validate user.
     * @param user User to validate
     * @return boolean
     */
    private boolean validateUser(User user) {
        return userChatChannelMap.containsKey(user);
    }

    private boolean isUserLoggedIn() {
        return user != null;
    }

    private boolean addUserInChatChannel(ChannelHandlerContext ctx, User user) {
        String chatChannelName = getChatChannelNameForUser(user);

        if(chatChannelName == null) {
            writeMessageFromContextHandler(ctx, "Can't restore chat channel name.");
            return false;
        }

        if(!chatChannelGroup.containsKey(chatChannelName)) {
            writeMessageFromContextHandler(ctx, "Can't restore " +
                    user.getLogin() + " in chat channel " + chatChannelName);
            return false;
        }
        assignUserToChatChannel(ctx, chatChannelName);
        writeMessageFromContextHandler(ctx, "Restore " + user.getLogin()
                + " in chat channel " + chatChannelName);
        return true;
    }

    private void assignUserToChatChannel(ChannelHandlerContext ctx, String chatChannelName) {
        ChannelGroup channelGroup = chatChannelGroup.get(chatChannelName);
        if(!checkChannelInGroup(channelGroup, ctx.channel())) {
            channelGroup.add(ctx.channel());
            writeMessageFromContextHandler(ctx, "You have been added to chat channel " + chatChannelName);
        }
        channelGroup.writeAndFlush("User " + ctx.channel().attr(USER_ATTRIBUTE_KEY).get().getLogin()
                + " joined to " + chatChannelName +  " channel.\r\n");
        userChatChannelMap.replace(user, chatChannelName);
    }

    private String getChatChannelNameForUser(User user) {
        return userChatChannelMap.get(user);
    }

    /**
     * Checks if ChannelGroup contains current user Channel
     * @param chatChannelGroup
     * @param ch
     */
    private boolean checkChannelInGroup(ChannelGroup chatChannelGroup, Channel ch) {
        if(chatChannelGroup == null) return false;
        return chatChannelGroup.contains(ch);
    }

    protected void showAllUsers(ChannelHandlerContext ctx) {
/*
        StringBuilder sb = new StringBuilder();
        for(User user : userChatChannelMap.keySet()) {
            sb.append(user.getLogin());
            sb.append("\r\n");
        }
        writeMessageFromContextHandler(ctx, sb.toString());
        */
        writeMessageFromContextHandler(ctx, user.toString());

    }

    private void showAllChannels(ChannelHandlerContext ctx) {
        /*
        StringBuilder sb = new StringBuilder();
        sb.append("List of channels:\r\n");
        for(String channelName : chatChannelGroup.keySet()) {
            sb.append(channelName);
            sb.append("\r\n");
        }
                writeMessageFromContextHandler(ctx, sb.toString());

        */

    }

    private void showAllUsersFromChannel(ChannelHandlerContext ctx)
            throws InvalidChatChannelName, InvalidChatChannelGroup {
        User user = ctx.channel().attr(USER_ATTRIBUTE_KEY).get();
        String chatChannelName = userChatChannelMap.get(user);

        if(chatChannelName == null) throw new InvalidChatChannelName("Can't find chat channel name.");

        ChannelGroup channelGroup = chatChannelGroup.get(chatChannelName);
        if(channelGroup == null) throw new InvalidChatChannelGroup("Something bad happens. Empty channel.");

        StringBuilder sb = new StringBuilder();

        sb.append("Users of channel - ").append(chatChannelName).append(":\r\n");
        for (Channel ch : channelGroup) {
            sb.append(ch.attr(USER_ATTRIBUTE_KEY).get().getLogin()).append("\r\n");
        }
        ctx.writeAndFlush(sb.toString());
    }

    private void showMessage(ChannelHandlerContext ctx, String message) {
        String chatChannelName = userChatChannelMap.get(user);
        ChannelGroup channelGroup = chatChannelGroup.get(chatChannelName);
        if(channelGroup != null) {
            for(Channel ch : channelGroup) {
                if(ch != ctx.channel()) {
                    ch.writeAndFlush( "[" + getUserLogin(ctx) + "] " + message + "\r\n");
                } else {
                    ch.writeAndFlush("[you] " + message + "\r\n");
                }
            }
            addMessageToList(chatChannelName, message, user);
        } else {
            writeMessageFromContextHandler(ctx, "You're not in any channels.");
        }
    }

    private String getUserLogin(ChannelHandlerContext ctx) {
        User user = ctx.channel().attr(USER_ATTRIBUTE_KEY).get();
        if(user != null)
            return user.getLogin();
        return ctx.channel().remoteAddress().toString();
    }

    private void writeMessageFromContextHandler(ChannelHandlerContext ctx, String message) {
        ctx.writeAndFlush(message + "\r\n");
    }

    private void addMessageToList(String chatChannelName, String message, User user) {
        BlockingQueue<String> messages = lastMessages.get(chatChannelName);
        if(messages.size() >= QUANTITY_OF_SHOWING_MESSAGES) messages.poll();
        messages.offer("[" + user.getLogin() + "]" + message);
    }

    private void printMessages(ChannelHandlerContext ctx, String chatChannelName) {
        StringBuilder sb = new StringBuilder();
        BlockingQueue<String> messages = lastMessages.get(chatChannelName);
        int i = 1;
        for(String message : messages) {
            sb.append(i).append(") ").append(message).append("\r\n");
            i++;
        }
        writeMessageFromContextHandler(ctx, sb.toString());
    }
}