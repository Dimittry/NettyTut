package com.nettytut.server;

import com.nettytut.initializer.ChatServerInitializer;
import com.nettytut.model.User;
import io.netty.bootstrap.ServerBootstrap;
import io.netty.channel.Channel;
import io.netty.channel.ChannelFuture;
import io.netty.channel.ChannelInitializer;
import io.netty.channel.EventLoopGroup;
import io.netty.channel.group.ChannelGroup;
import io.netty.channel.group.DefaultChannelGroup;
import io.netty.channel.nio.NioEventLoopGroup;
import io.netty.channel.socket.nio.NioServerSocketChannel;
import io.netty.util.concurrent.ImmediateEventExecutor;

import java.net.InetSocketAddress;
import java.util.HashMap;
import java.util.Map;

public class ChatServer {
    private final ChannelGroup channelGroup =
            new DefaultChannelGroup(ImmediateEventExecutor.INSTANCE);
    private final Map<String, ChannelGroup> channelGroups = new HashMap<>();
   // private final Map<Channel, User> userGroup = new HashMap<>();
    private final Map<User, String> userChatChannelMap = new HashMap<>();
    private final EventLoopGroup group = new NioEventLoopGroup();
    private Channel channel;

    public ChannelFuture start(InetSocketAddress address) {
        initChannelGroups();
        ServerBootstrap bootstrap = new ServerBootstrap();
        bootstrap.group(group)
                .channel(NioServerSocketChannel.class)
                //.childHandler(createInitializer(channelGroup));
                .childHandler(createInitializer(channelGroups, userChatChannelMap));
        ChannelFuture future = bootstrap.bind(address);
        future.syncUninterruptibly();
        channel = future.channel();
        return future;
    }

    protected void initChannelGroups() {
        channelGroups.put("zepto", new DefaultChannelGroup(ImmediateEventExecutor.INSTANCE));
    }

    protected ChannelInitializer<Channel> createInitializer(
            Map<String, ChannelGroup> group,
            Map<User, String> userChatChannelMap) {
        return new ChatServerInitializer(group, userChatChannelMap);
    }

    public void destroy() {
        if(channel != null) {
            channel.close();
        }
        channelGroup.close();
        group.shutdownGracefully();
    }

    public static void main(String[] args) throws Exception {
        if(args.length != 1) {
            System.err.println("Please give port as argument");
            System.exit(1);
        }
        int port = Integer.parseInt(args[0]);
        final ChatServer endpoint = new ChatServer();
        ChannelFuture future = endpoint.start(
                new InetSocketAddress(port));
        Runtime.getRuntime().addShutdownHook(new Thread() {
            @Override
            public void run() {
                endpoint.destroy();
            }
        });
        future.channel().closeFuture().syncUninterruptibly();
    }

}
