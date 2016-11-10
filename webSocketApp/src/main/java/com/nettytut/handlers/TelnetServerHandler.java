package com.nettytut.handlers;


import com.nettytut.model.User;
import io.netty.channel.*;
import io.netty.channel.ChannelHandler.Sharable;
import io.netty.channel.group.ChannelGroup;

import java.net.InetAddress;
import java.util.Date;
import java.util.HashMap;
import java.util.Iterator;
import java.util.Map;

/**
 * Handles a server-side channel.
 */
@Sharable
public class TelnetServerHandler extends SimpleChannelInboundHandler<String> {
    static final Map<String, String> userChannelGroupMap;

    static {
        userChannelGroupMap = new HashMap<>();
    }

    private final Map<String, ChannelGroup> group;
    private final Map<Channel, User> userGroup;

    public TelnetServerHandler(Map<String, ChannelGroup> group, Map<Channel, User> userGroup) {
        this.group = group;
        this.userGroup = userGroup;
    }

    @Override
    public void channelActive(ChannelHandlerContext ctx) throws Exception {
        // Send greeting for a new connection.
        ctx.write("Welcome to " + InetAddress.getLocalHost().getHostName() + "!\r\n");
        ctx.write("It is " + new Date() + " now.\r\n");
        //group.writeAndFlush("Client " + ctx.channel() + " joined");
        //group.add(ctx.channel());
        ctx.flush();
    }

    @Override
    public void channelRead0(ChannelHandlerContext ctx, String request) throws Exception {
        // Generate and write a response.
        String response;
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
            if(checkUserAuthorization(ctx)) {
                showMessage(ctx, request);
            } else {
                writeMessageFromContextHandler(ctx, "You're not sign in.\r\n");
            }
        }
        // We do not need to write a ChannelBuffer here.
        // We know the encoder inserted at TelnetPipelineFactory will do the conversion.
        //ChannelFuture future = ctx.write(response);
        //ChannelFuture future = ctx.write(group.toString());

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
        String joinChannelName = "";
        ChannelGroup channelGroup;
        String[] params = request.split(" ");
        if (params.length > 1) {
            joinChannelName = params[1];
        }
        channelGroup = group.get(joinChannelName);
        if (channelGroup != null) {
            channelGroup.writeAndFlush("Client " + userGroup.get(ctx.channel()).getLogin()
                    + " joined to " + joinChannelName + "channel.\r\n");
            writeMessageFromContextHandler(ctx, "You're successfully joined to channel " +
                    joinChannelName + ".\r\n");
            channelGroup.add(ctx.channel());
        } else {
            writeMessageFromContextHandler(ctx, "There is no channel with name " + joinChannelName + "\r\n"
                + "Try command <channels> to see all channels.\r\n");
        }
    }

    protected void authorizeUser(ChannelHandlerContext ctx, String request) {
        String[] params = request.split(" ");
        if(params.length < 3) {
            writeMessageFromContextHandler(ctx, "Wrong login/password pair. \r\n");
            return;
        }
        String login = params[1];
        String password = params[2];
        ctx.write(ctx.channel().hashCode()+"\r\n");
        User userFromGroup = userGroup.get(ctx.channel());
        if(userFromGroup == null) {
            userGroup.put(ctx.channel(), new User(login, password));
            writeMessageFromContextHandler(ctx, "You're successfully logged in." +
                    " Your login is " + login + " \r\n");
        } else if(userFromGroup.getPassword() != password) {
            writeMessageFromContextHandler(ctx, "Wrong password.\r\n");
            return;
        }
    }

    protected boolean checkUserAuthorization(ChannelHandlerContext ctx) {
        User user = userGroup.get(ctx.channel());
        if(user == null)
            return false;
        return true;
    }

    protected void showAllUsers(ChannelHandlerContext ctx) {
        StringBuilder sb = new StringBuilder();
        for(Channel channel : userGroup.keySet()) {
            sb.append(userGroup.get(channel).getLogin());
            sb.append("\r\n");
        }
        writeMessageFromContextHandler(ctx, sb.toString());
    }

    protected void showAllChannels(ChannelHandlerContext ctx) {
        StringBuilder sb = new StringBuilder();
        sb.append("List of channels:\r\n");
        for(String channelName : group.keySet()) {
            sb.append(channelName);
            sb.append("\r\n");
        }
        writeMessageFromContextHandler(ctx, sb.toString());
    }

    protected void showAllUsersFromChannel(ChannelHandlerContext ctx) {
        StringBuilder sb = new StringBuilder();
        Iterator it = group.entrySet().iterator();
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
        /*
                for (Channel c : group) {
                    if (c != ctx.channel()) {
                        c.writeAndFlush("[" + ctx.channel().remoteAddress() + "] " + request + "\r\n");
                    } else {
                        c.writeAndFlush("[you] " + request + "\r\n");
                    }
                }
            */
        Iterator it = group.entrySet().iterator();
        while (it.hasNext()) {
            Map.Entry<String, ChannelGroup> pair = (Map.Entry)it.next();
            if(pair.getValue().contains(ctx.channel())) {
                for (Channel c : pair.getValue()) {
                    if (c != ctx.channel()) {
                        c.writeAndFlush("[" + userGroup.get(ctx.channel()).getLogin() + "] " + message + "\r\n");
                    } else {
                        c.writeAndFlush("[you] " + message + "\r\n");
                    }
                }
            } else {
                writeMessageFromContextHandler(ctx, "You need to join a channel to write messages.\r\n");
            }
        }
    }

    protected void writeMessageFromContextHandler(ChannelHandlerContext ctx, String message) {
        ctx.writeAndFlush(message);
    }
}