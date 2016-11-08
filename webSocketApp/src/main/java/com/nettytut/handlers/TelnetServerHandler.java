package com.nettytut.handlers;


import io.netty.channel.*;
import io.netty.channel.ChannelHandler.Sharable;
import io.netty.channel.group.ChannelGroup;

import java.net.InetAddress;
import java.util.Date;

/**
 * Handles a server-side channel.
 */
@Sharable
public class TelnetServerHandler extends SimpleChannelInboundHandler<String> {
    private final ChannelGroup group;

    public TelnetServerHandler(ChannelGroup group) {
        this.group = group;
    }

    @Override
    public void channelActive(ChannelHandlerContext ctx) throws Exception {
        // Send greeting for a new connection.
        ctx.write("Welcome to " + InetAddress.getLocalHost().getHostName() + "!\r\n");
        ctx.write("It is " + new Date() + " now.\r\n");
        group.writeAndFlush("Client " + ctx.channel() + " joined");
        group.add(ctx.channel());
        ctx.flush();
    }

    @Override
    public void channelRead0(ChannelHandlerContext ctx, String request) throws Exception {
        // Generate and write a response.
        String response;
        boolean close = false;
        if (request.isEmpty()) {
            response = "Please type something.\r\n";
        } else if ("bye".equals(request.toLowerCase())) {
            response = "Have a good day!\r\n";
            close = true;
        } else {
            response = "Did you say '" + request + "'?\r\n";
            for (Channel c : group) {
                if (c != ctx.channel()) {
                    c.writeAndFlush("[" + ctx.channel().remoteAddress() + "] " + request + "\r\n");
                } else {
                    c.writeAndFlush("[you] " + request + "\r\n");
                }
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
}