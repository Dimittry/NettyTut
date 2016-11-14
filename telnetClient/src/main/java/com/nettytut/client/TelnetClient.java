package com.nettytut.client;

import com.nettytut.initializer.TelnetClientInitializer;
import io.netty.bootstrap.Bootstrap;
import io.netty.channel.Channel;
import io.netty.channel.ChannelFuture;
import io.netty.channel.EventLoopGroup;
import io.netty.channel.nio.NioEventLoopGroup;
import io.netty.channel.socket.nio.NioSocketChannel;

import java.io.BufferedReader;
import java.io.InputStreamReader;

/**
 * Created by Dimon on 14.11.2016.
 */
public class TelnetClient {
    static final String HOST = "127.0.0.1";
    static final int PORT = 9977;

    public static void main(String[] args) throws Exception {
        EventLoopGroup group = new NioEventLoopGroup();
        try {
            for(int i = 0; i < 15; i++) {

                Bootstrap b = new Bootstrap();
                b.group(group)
                        .channel(NioSocketChannel.class)
                        .handler(new TelnetClientInitializer());

                Channel ch = b.connect(HOST, PORT).sync().channel();
                ChannelFuture lastWriteFuture = null;
                BufferedReader in = new BufferedReader(new InputStreamReader(System.in));
                ch.writeAndFlush("login qwer" + i + " 123 " + "\r\n");
                lastWriteFuture = ch.writeAndFlush("join zepto" + "\r\n");
                /*
                for (; ; ) {
                    String line = in.readLine();
                    if (line == null) break;

                    lastWriteFuture = ch.writeAndFlush(line + "\r\n");
                    if ("bye".equals(line.toLowerCase())) {
                        ch.closeFuture().sync();
                        break;
                    }
                }
                */
                if (lastWriteFuture != null) {
                    lastWriteFuture.sync();
                }
            }
        } finally {
            group.shutdownGracefully();
        }
    }
}
