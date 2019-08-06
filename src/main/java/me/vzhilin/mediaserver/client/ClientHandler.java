package me.vzhilin.mediaserver.client;

import io.netty.buffer.ByteBuf;
import io.netty.buffer.ByteBufUtil;
import io.netty.channel.*;
import io.netty.handler.codec.http.*;
import io.netty.handler.codec.rtsp.RtspHeaderNames;
import io.netty.handler.codec.rtsp.RtspMethods;
import io.netty.handler.codec.rtsp.RtspVersions;
import io.netty.util.AttributeKey;

public class ClientHandler extends SimpleChannelInboundHandler<HttpObject> {
    private ConnectionStatistics statistics;

    private enum State {
        SETUP,
        PLAY,
    }

    public ClientHandler() {
    }

    private State state;

    @Override
    public void channelRegistered(ChannelHandlerContext ctx) throws Exception {
        super.channelRegistered(ctx);
    }

    @Override
    public void exceptionCaught(ChannelHandlerContext ctx, Throwable cause) throws Exception {
        cause.printStackTrace(System.err);
        ctx.close();
        if (cause instanceof OutOfMemoryError) {
            System.exit(1);
        }
    }

    @Override
    public void channelActive(ChannelHandlerContext ctx) throws Exception {
        super.channelActive(ctx);

        statistics =
            ctx.channel().attr(AttributeKey.<ConnectionStatistics>valueOf("stat")).get();

        String url =
            ctx.channel().attr(AttributeKey.<String>valueOf("url")).get();

        state = State.SETUP;

        HttpRequest request =
            new DefaultFullHttpRequest(RtspVersions.RTSP_1_0,
                RtspMethods.SETUP, url + "/TrackID=0");

        request.headers().set(RtspHeaderNames.CSEQ, 1);
        request.headers().set(RtspHeaderNames.TRANSPORT, "RTP/AVP/TCP;unicast;interleaved=0-1");
        ctx.writeAndFlush(request);
    }

    @Override
    protected void channelRead0(ChannelHandlerContext ctx, HttpObject msg) {
        if (msg instanceof HttpResponse) {
            HttpResponse response = (HttpResponse) msg;

            switch (state) {
                case SETUP:
                    String url =
                            ctx.channel().attr(AttributeKey.<String>valueOf("url")).get();

                    String cseq = response.headers().get(RtspHeaderNames.CSEQ);
                    String sessionid = response.headers().get(RtspHeaderNames.SESSION);

                    HttpRequest playRequest = new DefaultFullHttpRequest(RtspVersions.RTSP_1_0,
                            RtspMethods.PLAY, url);
                    playRequest.headers().set(RtspHeaderNames.CSEQ, cseq);
                    playRequest.headers().set(RtspHeaderNames.SESSION, sessionid);
                    ctx.writeAndFlush(playRequest);

                    state = State.PLAY;
                    break;

                case PLAY:
                    ctx.channel().pipeline().addLast(new SimpleChannelInboundHandler<InterleavedPacket>() {
                        @Override
                        protected void channelRead0(ChannelHandlerContext ctx, InterleavedPacket msg)  {
                            ByteBuf payload = msg.getPayload();
                            statistics.onRead(payload.readableBytes());
                            payload.release();
                        }
                    });
                    break;
            }
        }

    }
}
