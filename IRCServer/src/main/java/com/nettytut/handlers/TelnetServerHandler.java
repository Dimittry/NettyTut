package com.nettytut.handlers;


import com.nettytut.exceptions.InvalidChatChannelGroup;
import com.nettytut.exceptions.InvalidChatChannelName;
import com.nettytut.model.User;
import io.netty.channel.Channel;
import io.netty.channel.ChannelHandler.Sharable;
import io.netty.channel.ChannelHandlerContext;
import io.netty.channel.SimpleChannelInboundHandler;
import io.netty.channel.group.ChannelGroup;
import io.netty.channel.group.DefaultChannelGroup;
import io.netty.util.AttributeKey;
import io.netty.util.concurrent.ImmediateEventExecutor;

import java.net.InetAddress;
import java.util.Date;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.BlockingQueue;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.LinkedBlockingQueue;
import java.util.concurrent.atomic.AtomicBoolean;

/**
 * Handles a server-side channel.
 */
@Sharable
public class TelnetServerHandler extends SimpleChannelInboundHandler<String> {
    /* Holds the name of ChannelGroup and the ChannelGroup */
    private final Map<String, ChannelGroup> chatChannelGroup;
    /* Holds pairs of user and related name of ChannelGroup from chatChannelGroup */
    private final Map<User, String> userChatChannelMap;

    private User user;
    /**
    * Defines if need to save place in chat channel group for user
    * when he has disconnected.
    */
    private static AtomicBoolean isSavePlace = new AtomicBoolean(true);
    private final static AttributeKey<User> USER_ATTRIBUTE_KEY = AttributeKey.valueOf("user");
    private final static Map<String, BlockingQueue<String>> lastMessages;
    private final static ChannelGroup activeUsers = new DefaultChannelGroup(ImmediateEventExecutor.INSTANCE);
    private final static int QUANTITY_OF_SHOWING_MESSAGES = 10;
    private final static int GROUP_CAPACITY = 2;
    private final static String EMPTY_CHAT_GROUP_NAME = "empty";
    private final static String SET_SAVE_PLACE_TO_FALSE = "0";

    static {
        lastMessages = new ConcurrentHashMap<>();
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
        activeUsers.add(ctx.channel());
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
        } else if (request.startsWith("login")) {
            try {
                authorizeUser(ctx, request);
            } catch(IllegalArgumentException | IllegalStateException e){
                writeMessageFromContextHandler(ctx, e.getMessage());
            }
        } else if (request.startsWith("saveplace")) {
            changeSavePlace(request);
        } else if (request.startsWith("join")) {
            try {
                joinUserToChannel(request, ctx);
            } catch(IllegalStateException e) {
                writeMessageFromContextHandler(ctx, e.getMessage());
            }
        } else if ("activeusers".equals(request.toLowerCase())) {
            showActiveUsers(ctx);
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
                writeMessageFromContextHandler(ctx, "You need to sign in to write the messages.");
            }
        }
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

    private void authorizeUser(ChannelHandlerContext ctx, String request) {
        String[] params = request.split(" ");
        if(params.length < 3)
            throw new IllegalArgumentException("Wrong login/password pair.");

        String login = params[1];
        String password = params[2];
        User newUser = new User(login, password);
        if (user != null || newUser.equals(user))
            throw new IllegalStateException("You're already signed in.");
        if(isUserExists(newUser))
            throw new IllegalStateException("Such user already exists.");
        synchronized (this) {
            User savedUser = getSavedUserByLogin(login);
            if (savedUser == null) {
                userChatChannelMap.put(newUser, EMPTY_CHAT_GROUP_NAME);
                user = newUser;
                ctx.channel().attr(USER_ATTRIBUTE_KEY).set(newUser);
                writeMessageFromContextHandler(ctx, "You're successfully signed up." +
                        " Your login is " + login);
            } else {
                    if(savedUser.equals(newUser)) {
                        user = newUser;
                        writeMessageFromContextHandler(ctx, "You're successfully signed in." +
                                " Your login is " + login);
                    } else {
                        writeMessageFromContextHandler(ctx, "Wrong password for login " + login);
                        return;
                    }
                    ctx.channel().attr(USER_ATTRIBUTE_KEY).set(newUser);
                    addUserToChatChannel(ctx, newUser);
            }
        }
    }

    private void joinUserToChannel(String request, ChannelHandlerContext ctx) {
        if(!isUserLoggedIn())
            throw new IllegalStateException("You're not logged in.");
        String chatChannelName;
        synchronized (this) {
            if (isUserInGroup())
                throw new IllegalStateException("You're already in chat channel " + getChatChannelNameForUser(user));
            String[] params = request.split(" ");
            if (params.length != 2) return;
            chatChannelName = params[1];
            if (isChatChannelGroupFull(chatChannelName, ctx))
                throw new IllegalStateException("There is no place in channel " + chatChannelName);
            if (!chatChannelGroup.containsKey(chatChannelName))
                throw new IllegalStateException("There is no channels with name " + chatChannelName);
            ChannelGroup channelGroup = chatChannelGroup.get(chatChannelName);
            if (!channelGroup.contains(ctx.channel()))
                assignUserToChatChannel(ctx, chatChannelName);
        }
        printMessages(ctx, chatChannelName);
    }

    private boolean isChatChannelGroupFull(String chatChannelName, ChannelHandlerContext ctx) {
        if(isSavePlace.get()) {
            return checkWithSavePlace(chatChannelName, ctx);
        } else {
            return checkWithoutSavePlace(chatChannelName);
        }
    }

    private void validateUserChatChannel() {
        boolean isUserInChatChannel = false;
        String chatChannelNameForUser = userChatChannelMap.get(user);
        if(chatChannelNameForUser == null) return;
        ChannelGroup chg = chatChannelGroup.get(chatChannelNameForUser);
        if(chg != null) {
            for(Channel ch : chg) {
                if(ch.attr(USER_ATTRIBUTE_KEY).equals(user)) {
                    isUserInChatChannel = true;
                    break;
                }
            }
        }
        if(!isUserInChatChannel) userChatChannelMap.replace(user, EMPTY_CHAT_GROUP_NAME);
    }

    private boolean checkWithoutSavePlace(String chatChannelName) {
        validateUserChatChannel();
        ChannelGroup chg = chatChannelGroup.get(chatChannelName);
        if(chg == null) return false;
        return (chg.size() >= GROUP_CAPACITY);
    }

    private boolean checkWithSavePlace(String chatChannelName, ChannelHandlerContext ctx) {
        User user = ctx.channel().attr(USER_ATTRIBUTE_KEY).get();
        int count = 0;
        for(Map.Entry<User, String> entry : userChatChannelMap.entrySet()) {
            if(entry.getKey().equals(user) && entry.getValue() != EMPTY_CHAT_GROUP_NAME) return false;
            if (entry.getValue().equals(chatChannelName)) count++;
        }
        return count >= GROUP_CAPACITY;
    }

    private boolean isUserInGroup() {
        String chatChannelName = getChatChannelNameForUser(user);
        return chatChannelName != EMPTY_CHAT_GROUP_NAME;
    }

    private User getSavedUserByLogin(String login) {
        Set<User> users = userChatChannelMap.keySet();
        for(User iUser : users) {
            if(iUser.getLogin().equals(login)) return iUser;
        }
        return null;
    }

    private boolean isUserLoggedIn() {
        return user != null;
    }

    private boolean addUserToChatChannel(ChannelHandlerContext ctx, User user) {
        String chatChannelName = getChatChannelNameForUser(user);

        if(chatChannelName == null) {
            writeMessageFromContextHandler(ctx, "Can't restore chat channel name.");
            return false;
        }
        synchronized (this) {
            if(!chatChannelGroup.containsKey(chatChannelName)) {
                writeMessageFromContextHandler(ctx, "Can't restore " +
                        user.getLogin() + " in chat channel " + chatChannelName);
                return false;
            }
            if (isChatChannelGroupFull(chatChannelName, ctx)) {
                writeMessageFromContextHandler(ctx, "There is no place in channel " + chatChannelName);
                return false;
            }
            assignUserToChatChannel(ctx, chatChannelName);
            writeMessageFromContextHandler(ctx, "Restore " + user.getLogin()
                    + " in chat channel " + chatChannelName);
        }
        printMessages(ctx, chatChannelName);

        return true;
    }

    private void assignUserToChatChannel(ChannelHandlerContext ctx, String chatChannelName) {
        ChannelGroup channelGroup = chatChannelGroup.get(chatChannelName);
        if(!checkUserChannelInGroup(channelGroup, ctx.channel())) {
            channelGroup.add(ctx.channel());
            //writeMessageFromContextHandler(ctx, "You have been added to chat channel " + chatChannelName);
        }
        channelGroup.writeAndFlush("User " + ctx.channel().attr(USER_ATTRIBUTE_KEY).get().getLogin()
                + " joined to " + chatChannelName +  " channel.\r\n");
        userChatChannelMap.replace(user, chatChannelName);
    }

    private String getChatChannelNameForUser(User user) {
        return userChatChannelMap.get(user);
    }

    private synchronized boolean isUserExists(User user) {
        for(Channel ch : activeUsers) {
            User chUser = ch.attr(USER_ATTRIBUTE_KEY).get();
            if(chUser != null && user.getLogin().equals(chUser.getLogin()))
                return true;
        }
        return false;
    }

    /**
     * Checks if ChannelGroup contains current user Channel
     * @param chatChannelGroup
     * @param ch
     */
    private boolean checkUserChannelInGroup(ChannelGroup chatChannelGroup, Channel ch) {
        if(chatChannelGroup == null) return false;
        return chatChannelGroup.contains(ch);
    }

    private void showAllUsersFromChannel(ChannelHandlerContext ctx)
            throws InvalidChatChannelName, InvalidChatChannelGroup {
        User user = ctx.channel().attr(USER_ATTRIBUTE_KEY).get();
        String chatChannelName = userChatChannelMap.get(user);

        if(chatChannelName == null) throw new InvalidChatChannelName("Can't find chat channel name.");

        ChannelGroup channelGroup = chatChannelGroup.get(chatChannelName);
        if(channelGroup == null) throw new InvalidChatChannelGroup("Can't find channel group.");

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
        synchronized (this) {
            if(messages.size() >= QUANTITY_OF_SHOWING_MESSAGES) messages.poll();
            messages.offer(String.format("[%s]%s", user.getLogin(), message));
        }
    }

    private void changeSavePlace(String request) {
        String[] params = request.split(" ");

        if(params.length != 2) return;

        if(params[1].equals(SET_SAVE_PLACE_TO_FALSE))
            isSavePlace.set(false);
        else
            isSavePlace.set(true);
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

    private synchronized void showActiveUsers(ChannelHandlerContext ctx) {
        for (Channel ch : activeUsers) {
            User tmpUser = ch.attr(USER_ATTRIBUTE_KEY).get();
            String msg = (tmpUser != null) ? tmpUser.getLogin() : ch.toString();
            writeMessageFromContextHandler(ctx, msg);
        }
    }
}