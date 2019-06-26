package me.vzhilin.mediaserver.media;

import io.netty.buffer.ByteBuf;
import io.netty.buffer.PooledByteBufAllocator;
import me.vzhilin.mediaserver.util.AVCCExtradataParser;
import org.bytedeco.javacpp.*;

import java.io.File;
import java.io.FileNotFoundException;
import java.io.IOException;
import java.util.LinkedList;
import java.util.Queue;

import static org.bytedeco.javacpp.avcodec.*;
import static org.bytedeco.javacpp.avformat.*;

public class FileMediaPacketSource implements MediaPacketSource {
    private boolean wasClosed;
    private MediaPacketSourceDescription desc;
    private Queue<MediaPacket> packetQueue = new LinkedList<>();
    private AVPacket pk;
    private AVFormatContext pAvfmtCtx;

    public FileMediaPacketSource(File file) throws IOException {
        if (file.exists()) {
            open(file);
        } else {
            throw new FileNotFoundException("file not found: " + file);
        }
    }

    private void open(File file) throws IOException {
        pAvfmtCtx = new avformat.AVFormatContext(null);
        int r = avformat_open_input(pAvfmtCtx, new BytePointer(file.getAbsolutePath()), null, null);
        if (r < 0) {
            pAvfmtCtx.close();
            wasClosed = true;
            throw new IOException("avformat_open_input error: " + r);
        }
        r = avformat_find_stream_info(pAvfmtCtx, (PointerPointer) null);
        if (r < 0) {
            wasClosed = true;
            avformat_close_input(pAvfmtCtx);
            pAvfmtCtx.close();
            throw new IOException("error: " + r);
        }
        pk = new avcodec.AVPacket();
        AVStream avStream = getVideoStream();
        if (avStream == null) {
            close();
            throw new IOException("h264 stream not found");
        }
        int videoStreamId = avStream.index();
        avutil.AVRational streamTimebase = avStream.time_base();
        avutil.AVRational avgFrameRate = avStream.avg_frame_rate();
        avcodec.AVCodecParameters cp = avStream.codecpar();
        byte[] extradataBytes = new byte[cp.extradata_size()];
        cp.extradata().get(extradataBytes);
        AVCCExtradataParser extradata = new AVCCExtradataParser(extradataBytes);
        byte[] sps = extradata.getSps();
        byte[] pps = extradata.getPps();
        desc = new MediaPacketSourceDescription();
        desc.setSps(sps);
        desc.setPps(pps);
        desc.setTimebase(streamTimebase);
        desc.setAvgFrameRate(avgFrameRate);
        desc.setVideoStreamId(videoStreamId);
        fillQueue();
    }

    private AVStream getVideoStream() {
        int ns = pAvfmtCtx.nb_streams();
        for (int i = 0; i < ns; i++) {
            AVStream pstream = pAvfmtCtx.streams(i);
            avcodec.AVCodecParameters cp = pstream.codecpar();
            if (cp.codec_id() == AV_CODEC_ID_H264) {
                return pstream;
            }
        }
        return null;
    }

    @Override
    public MediaPacketSourceDescription getDesc() {
        return desc;
    }

    @Override
    public boolean hasNext() {
        return !packetQueue.isEmpty();
    }

    @Override
    public MediaPacket next() {
        MediaPacket pkt = packetQueue.poll();
        if (packetQueue.isEmpty()) {
            fillQueue();
        }
        return pkt;
    }

    private boolean fillQueue() {
        while (true) {
            av_packet_unref(pk);
            if (av_read_frame(pAvfmtCtx, pk) < 0) {
                return true;
            }
            if (pk.stream_index() == desc.getVideoStreamId()) {
                long pts = pk.pts();
                long dts = pk.dts();
                boolean isKey = (pk.flags() & AV_PKT_FLAG_KEY) != 0;
                int sz = pk.size();
                byte[] data = new byte[sz];
                pk.data().get(data);
                int offset = 0;
                while (offset < data.length) {
                    int avccLen = ((data[offset] & 0xff) << 24) +
                            ((data[offset + 1] & 0xff) << 16) +
                            ((data[offset + 2] & 0xff) << 8) +
                            (data[offset + 3] & 0xff);
                    ByteBuf payload = PooledByteBufAllocator.DEFAULT.buffer(avccLen, avccLen);
                    payload.writeBytes(data, offset + 4, avccLen);
                    packetQueue.offer(new MediaPacket(pts, dts, isKey, payload));
                    offset += (avccLen + 4);
                }
                return false;
            }
        }
    }

    @Override
    public void close() {
        if (!wasClosed) {
            wasClosed = true;
            if (hasNext()) {
                av_packet_unref(pk);
            }
            avformat_close_input(pAvfmtCtx);
            pAvfmtCtx.close();
            pk.close();
        }
    }
}
