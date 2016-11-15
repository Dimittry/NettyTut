package com.nettytut.initializer;

import com.nettytut.handlers.TelnetServerHandler;
import com.nettytut.model.User;
import io.netty.channel.Channel;
import io.netty.channel.ChannelInitializer;
import io.netty.channel.ChannelPipeline;
import io.netty.channel.group.ChannelGroup;
import io.netty.handler.codec.DelimiterBasedFrameDecoder;
import io.netty.handler.codec.Delimiters;
import io.netty.handler.codec.string.StringDecoder;
import io.netty.handler.codec.string.StringEncoder;

import java.util.Map;

public class ChatServerInitializer
        extends ChannelInitializer<Channel>{
    private final Map<String, ChannelGroup> group;
    //private final Map<Channel, User> userGroup;
    private final Map<User, String> userChatChannel;
    private static final StringDecoder DECODER = new StringDecoder();
    private static final StringEncoder ENCODER = new StringEncoder();

    public ChatServerInitializer(Map<String, ChannelGroup> group,
                                 Map<User, String> userChatChannel) {
        this.group = group;
        this.userChatChannel = userChatChannel;
    }

    @Override
    protected void initChannel(Channel ch) throws Exception {
        ChannelPipeline pipeline = ch.pipeline();
        // Add the text line codec combination first,
        pipeline.addLast(new DelimiterBasedFrameDecoder(8192, Delimiters.lineDelimiter()));
        // the encoder and decoder are static as these are sharable
        pipeline.addLast(DECODER);
        pipeline.addLast(ENCODER);
        // and then business logic.
        pipeline.addLast(new TelnetServerHandler(group, userChatChannel));
    }
}
