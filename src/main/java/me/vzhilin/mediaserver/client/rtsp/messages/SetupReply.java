package me.vzhilin.mediaserver.client.rtsp.messages;

import me.vzhilin.mediaserver.client.rtsp.messages.sdp.SdpMessage;

public class SetupReply {
    private final String session;
    private SdpMessage sdp;

    public SetupReply(String session) {
        this.session = session;
    }
    public SdpMessage getMessage() {
        return sdp;
    }
    public String getSession() {
        return session;
    }
}
