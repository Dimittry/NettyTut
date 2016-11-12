package com.nettytut.handlers;


import com.nettytut.model.User;
import io.netty.channel.Channel;
import io.netty.channel.ChannelHandler.Sharable;
import io.netty.channel.ChannelHandlerContext;
import io.netty.channel.SimpleChannelInboundHandler;
import io.netty.channel.group.ChannelGroup;
import io.netty.util.AttributeKey;

import java.net.InetAddress;
import java.util.Date;
import java.util.Iterator;
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
    private static BlockingQueue<String> lastMessages = new LinkedBlockingQueue<>();
    private final static int quantityOfShowingMessages = 10;

    public TelnetServerHandler(Map<String, ChannelGroup> chatChannelGroup, Map<User, String> userChatChannelMap) {
        this.chatChannelGroup = chatChannelGroup;
        this.userChatChannelMap = userChatChannelMap;
    }

    @Override
    public void channelActive(ChannelHandlerContext ctx) throws Exception {
        // Send greeting for a new connection.
        ctx.write("Welcome to " + InetAddress.getLocalHost().getHostName() + "!\r\n");
        ctx.write("It is " + new Date() + " now.\r\n");
        //chatChannelGroup.writeAndFlush("Client " + ctx.channel() + " joined");
        //chatChannelGroup.add(ctx.channel());
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
            //showAllUsers(ctx);
            showAllUsersFromChannel(ctx);
        } else {
            //response = "Did you say '" + request + "'?\r\n";
            if(isUserLoggedIn()) {
                showMessage(ctx, request);
            } else {
                writeMessageFromContextHandler(ctx, "You're not sign in.\r\n");
            }
        }
        // We do not need to write a ChannelBuffer here.
        // We know the encoder inserted at TelnetPipelineFactory will do the conversion.
        //ChannelFuture future = ctx.write(response);
        //ChannelFuture future = ctx.write(chatChannelGroup.toString());

        // Close the connection after sending 'Have a good day!'
        // if the client has sent 'bye'.
        if (close) {
            //future.addListener(ChannelFutureListener.CLOSE);
            ctx.close();
        }
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

    protected void joinUserToChannel(String request, ChannelHandlerContext ctx) {
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
        if(!chatChannelGroup.containsKey(chatChannelName)) {
            writeMessageFromContextHandler(ctx, "There is no channels with name " + chatChannelName);
            return;
        }
        ChannelGroup channelGroup = chatChannelGroup.get(chatChannelName);
        if(!channelGroup.contains(ctx.channel())) {
            assignUserToChatChannel(ctx, chatChannelName);
        }
        printMessages(ctx);
    }

    protected boolean isUserInGroup() {
        String chatChannelName = getChatChannelNameForUser(user);
        return chatChannelName != null;
    }

    protected void authorizeUser(ChannelHandlerContext ctx, String request) {
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
    protected boolean validateUser(User user) {
        return userChatChannelMap.containsKey(user);
    }

    protected boolean isUserLoggedIn() {
        return user != null;
    }

    protected boolean addUserInChatChannel(ChannelHandlerContext ctx, User user) {
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

    protected void assignUserToChatChannel(ChannelHandlerContext ctx, String chatChannelName) {
        ChannelGroup channelGroup = chatChannelGroup.get(chatChannelName);
        if(!checkChannelInGroup(channelGroup, ctx.channel())) {
            channelGroup.add(ctx.channel());
            writeMessageFromContextHandler(ctx, "You have been added to chat channel " + chatChannelName);
        }
        channelGroup.writeAndFlush("User " + ctx.channel().attr(USER_ATTRIBUTE_KEY).get()
                + " joined to " + chatChannelName +  "channel.\r\n");
        userChatChannelMap.replace(user, chatChannelName);
    }

    protected String getChatChannelNameForUser(User user) {
        return userChatChannelMap.get(user);
    }

    /**
     * Checks if ChannelGroup contains current user Channel
     * @param chatChannelGroup
     * @param ch
     */
    protected boolean checkChannelInGroup(ChannelGroup chatChannelGroup, Channel ch) {
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

    protected void showAllChannels(ChannelHandlerContext ctx) {
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

    protected void showAllUsersFromChannel(ChannelHandlerContext ctx) {
        StringBuilder sb = new StringBuilder();
        Iterator it = chatChannelGroup.entrySet().iterator();
        while (it.hasNext()) {
            Map.Entry<String, ChannelGroup> pair = (Map.Entry)it.next();
            sb.append(pair.getKey());
            //pair.getValue().forEach(sb::append);

            for(Channel c : pair.getValue()) {
                sb.append(c);
                sb.append("\r\n");
            }

        }
        ctx.write(sb.toString());
    }

    protected void showMessage(ChannelHandlerContext ctx, String message) {
        ChannelGroup channelGroup = chatChannelGroup.get(userChatChannelMap.get(user));
        if(channelGroup != null) {
            for(Channel ch : channelGroup) {
                if(ch != ctx.channel()) {
                    ch.writeAndFlush( "[" + getUserLogin(ctx) + "] " + message + "\r\n");
                } else {
                    ch.writeAndFlush("[you] " + message + "\r\n");
                }
            }
            addMessageToList(message);
        }
    }

    protected String getUserLogin(ChannelHandlerContext ctx) {
        User user = ctx.channel().attr(USER_ATTRIBUTE_KEY).get();
        if(user != null)
            return user.getLogin();
        return ctx.channel().remoteAddress().toString();
    }

    protected void writeMessageFromContextHandler(ChannelHandlerContext ctx, String message) {
        ctx.writeAndFlush(message + "\r\n");
    }

    private void addMessageToList(String message) {
        if(lastMessages.size() >= quantityOfShowingMessages) {
            lastMessages.poll();
        }
        lastMessages.offer(message);
    }

    private void printMessages(ChannelHandlerContext ctx) {
        StringBuilder sb = new StringBuilder();
        for(int i = 0, j = lastMessages.size(); i < j; i++) {
        }
        int i = 0;
        for(String message : lastMessages) {
            sb.append(i).append(") ").append(message).append("\r\n");
            i++;
        }
        writeMessageFromContextHandler(ctx, sb.toString());
    }
}