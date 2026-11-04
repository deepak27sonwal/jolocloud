package com.cloudbackup.app;

import android.content.ContentResolver;
import android.content.Context;
import android.media.MediaCodec;
import android.media.MediaCodecInfo;
import android.media.MediaExtractor;
import android.media.MediaFormat;
import android.media.MediaMetadataRetriever;
import android.media.MediaMuxer;
import android.net.Uri;
import android.os.ParcelFileDescriptor;
import android.view.Surface;
import java.io.File;
import java.nio.ByteBuffer;
import java.util.ArrayList;
import java.util.List;

/**
 * Re-encodes a video to a lower bitrate at the SAME resolution and frame rate — nothing is downscaled,
 * only the encode is made more efficient — using Android's standard decoder-Surface -> encoder-Surface
 * pipeline (frames never touch raw pixel buffers in app code, which sidesteps the color-format bugs that
 * plague naive MediaCodec transcoders). Audio is copied through unchanged: no re-encode, no sync risk.
 *
 * On ANY failure, timeout, or if the result would not actually be smaller, this returns null and the
 * original file is uploaded untouched — nothing is ever deleted or modified in place.
 *
 * This pipeline has not been exercised on a real device in this environment. The approach mirrors
 * Android's own documented transcoding pattern (decoder output rendered straight into the encoder's
 * input Surface), but please check playback on the first several videos before trusting it fully.
 */
final class VideoTranscoder {
    private static final double TARGET_BITS_PER_PIXEL_PER_FRAME = 0.08; // ~"good quality" H.264 guideline
    private static final int MIN_BITRATE = 1_500_000;
    private static final int MAX_BITRATE = 12_000_000;
    private static final long MIN_SOURCE_BYTES = 3L * 1024 * 1024;  // not worth the battery/risk on tiny clips
    private static final long TIME_BUDGET_MS = 5 * 60 * 1000;        // give up and fall back past this
    private static final long DEQUEUE_TIMEOUT_US = 10_000;
    private static final int AUDIO_BUFFER_BYTES = 256 * 1024;

    private VideoTranscoder() { }

    /** @return the compressed file, or null if transcoding was skipped, failed, or didn't help. */
    static File compress(Context ctx, ContentResolver cr, Uri uri, long sourceSize) {
        if (sourceSize < MIN_SOURCE_BYTES) return null;
        File out = null;
        MediaExtractor extractor = null;
        MediaCodec decoder = null, encoder = null;
        MediaMuxer muxer = null;
        Surface encoderSurface = null;
        try {
            extractor = new MediaExtractor();
            try (ParcelFileDescriptor pfd = cr.openFileDescriptor(uri, "r")) {
                if (pfd == null) return null;
                extractor.setDataSource(pfd.getFileDescriptor());
            }

            int videoTrack = -1, audioTrack = -1;
            MediaFormat videoFormat = null, audioFormat = null;
            for (int i = 0; i < extractor.getTrackCount(); i++) {
                MediaFormat f = extractor.getTrackFormat(i);
                String mime = f.getString(MediaFormat.KEY_MIME);
                if (mime == null) continue;
                if (videoTrack < 0 && mime.startsWith("video/")) { videoTrack = i; videoFormat = f; }
                else if (audioTrack < 0 && mime.startsWith("audio/")) { audioTrack = i; audioFormat = f; }
            }
            if (videoTrack < 0 || videoFormat == null) return null;
            if (!videoFormat.containsKey(MediaFormat.KEY_WIDTH) || !videoFormat.containsKey(MediaFormat.KEY_HEIGHT)) return null;
            int width = videoFormat.getInteger(MediaFormat.KEY_WIDTH);
            int height = videoFormat.getInteger(MediaFormat.KEY_HEIGHT);
            if (width <= 0 || height <= 0) return null;
            float fps = videoFormat.containsKey(MediaFormat.KEY_FRAME_RATE) ? videoFormat.getInteger(MediaFormat.KEY_FRAME_RATE) : 30f;
            if (fps <= 0 || fps > 60) fps = 30f;

            long durationUs = videoFormat.containsKey(MediaFormat.KEY_DURATION) ? videoFormat.getLong(MediaFormat.KEY_DURATION) : 0;
            double durationSec = durationUs > 0 ? durationUs / 1_000_000.0 : 0;
            if (durationSec <= 0) return null; // can't tell if this would actually help: skip rather than guess
            long sourceBitrate = (long) (sourceSize * 8 / durationSec);

            long targetBitrate = (long) (width * height * fps * TARGET_BITS_PER_PIXEL_PER_FRAME);
            targetBitrate = Math.max(MIN_BITRATE, Math.min(MAX_BITRATE, targetBitrate));
            if (targetBitrate >= sourceBitrate * 0.9) return null; // wouldn't meaningfully shrink it: skip rather than risk it for nothing

            int rotation = 0;
            try (ParcelFileDescriptor pfd = cr.openFileDescriptor(uri, "r")) {
                if (pfd != null) {
                    MediaMetadataRetriever mmr = new MediaMetadataRetriever();
                    try {
                        mmr.setDataSource(pfd.getFileDescriptor());
                        String r = mmr.extractMetadata(MediaMetadataRetriever.METADATA_KEY_VIDEO_ROTATION);
                        if (r != null) rotation = Integer.parseInt(r);
                    } finally {
                        mmr.release();
                    }
                }
            } catch (Exception ignored) { }

            out = File.createTempFile("vid", ".mp4", ctx.getCacheDir());

            MediaFormat encFormat = MediaFormat.createVideoFormat(MediaFormat.MIMETYPE_VIDEO_AVC, width, height);
            encFormat.setInteger(MediaFormat.KEY_BIT_RATE, (int) targetBitrate);
            encFormat.setInteger(MediaFormat.KEY_FRAME_RATE, Math.round(fps));
            encFormat.setInteger(MediaFormat.KEY_I_FRAME_INTERVAL, 2);
            encFormat.setInteger(MediaFormat.KEY_COLOR_FORMAT, MediaCodecInfo.CodecCapabilities.COLOR_FormatSurface);

            encoder = MediaCodec.createEncoderByType(MediaFormat.MIMETYPE_VIDEO_AVC);
            encoder.configure(encFormat, null, null, MediaCodec.CONFIGURE_FLAG_ENCODE);
            encoderSurface = encoder.createInputSurface();
            encoder.start();

            decoder = MediaCodec.createDecoderByType(videoFormat.getString(MediaFormat.KEY_MIME));
            decoder.configure(videoFormat, encoderSurface, null, 0);
            decoder.start();

            muxer = new MediaMuxer(out.getPath(), MediaMuxer.OutputFormat.MUXER_OUTPUT_MPEG_4);
            if (rotation != 0) muxer.setOrientationHint(rotation);

            extractor.selectTrack(videoTrack);
            if (audioTrack >= 0) extractor.selectTrack(audioTrack);

            boolean ok = pump(extractor, decoder, encoder, muxer, videoTrack, audioTrack, audioFormat);
            if (!ok) { safeDelete(out); return null; }
            return out;
        } catch (Throwable t) {
            safeDelete(out);
            return null; // any problem at all: fall back to uploading the original, untouched
        } finally {
            try { if (decoder != null) { decoder.stop(); decoder.release(); } } catch (Exception ignored) { }
            try { if (encoder != null) { encoder.stop(); encoder.release(); } } catch (Exception ignored) { }
            try { if (encoderSurface != null) encoderSurface.release(); } catch (Exception ignored) { }
            try { if (muxer != null) muxer.release(); } catch (Exception ignored) { }
            try { if (extractor != null) extractor.release(); } catch (Exception ignored) { }
        }
    }

    private static boolean pump(MediaExtractor extractor, MediaCodec decoder, MediaCodec encoder, MediaMuxer muxer,
                                 int videoTrack, int audioTrack, MediaFormat audioFormat) {
        long deadline = System.currentTimeMillis() + TIME_BUDGET_MS;
        MediaCodec.BufferInfo decOutInfo = new MediaCodec.BufferInfo();
        MediaCodec.BufferInfo encOutInfo = new MediaCodec.BufferInfo();

        boolean extractorDoneVideo = false;
        boolean decoderDoneOutput = false;
        boolean encoderDoneOutput = false;
        int muxerVideoTrack = -1, muxerAudioTrack = -1;
        boolean muxerStarted = false;
        List<Object[]> pendingAudio = new ArrayList<>(); // [ByteBuffer, MediaCodec.BufferInfo] queued before muxer.start()

        while (!encoderDoneOutput) {
            if (System.currentTimeMillis() > deadline) return false; // taking too long: caller falls back to the original

            // 1) feed video samples to the decoder; copy audio samples straight through to the muxer
            if (!extractorDoneVideo) {
                int track = extractor.getSampleTrackIndex();
                if (track < 0) {
                    int inIdx = decoder.dequeueInputBuffer(DEQUEUE_TIMEOUT_US);
                    if (inIdx >= 0) {
                        decoder.queueInputBuffer(inIdx, 0, 0, 0, MediaCodec.BUFFER_FLAG_END_OF_STREAM);
                        extractorDoneVideo = true;
                    }
                } else if (track == audioTrack) {
                    ByteBuffer tmp = ByteBuffer.allocate(AUDIO_BUFFER_BYTES);
                    int sz = extractor.readSampleData(tmp, 0);
                    if (sz >= 0) {
                        tmp.limit(sz);
                        MediaCodec.BufferInfo info = new MediaCodec.BufferInfo();
                        info.size = sz;
                        info.presentationTimeUs = extractor.getSampleTime();
                        info.flags = extractor.getSampleFlags();
                        if (muxerStarted) muxer.writeSampleData(muxerAudioTrack, tmp, info);
                        else pendingAudio.add(new Object[]{tmp, info});
                    }
                    extractor.advance();
                } else if (track == videoTrack) {
                    int inIdx = decoder.dequeueInputBuffer(DEQUEUE_TIMEOUT_US);
                    if (inIdx >= 0) {
                        ByteBuffer buf = decoder.getInputBuffer(inIdx);
                        int sz = buf == null ? -1 : extractor.readSampleData(buf, 0);
                        if (sz < 0) {
                            decoder.queueInputBuffer(inIdx, 0, 0, 0, MediaCodec.BUFFER_FLAG_END_OF_STREAM);
                            extractorDoneVideo = true;
                        } else {
                            long pts = extractor.getSampleTime();
                            decoder.queueInputBuffer(inIdx, 0, sz, pts, 0);
                            extractor.advance();
                        }
                    }
                } else {
                    extractor.advance(); // a track we didn't select somehow surfaced: skip past it defensively
                }
            }

            // 2) drain the decoder: rendering (render=true) pushes the frame straight into the encoder's input surface
            if (!decoderDoneOutput) {
                int outIdx = decoder.dequeueOutputBuffer(decOutInfo, DEQUEUE_TIMEOUT_US);
                if (outIdx >= 0) {
                    boolean eos = (decOutInfo.flags & MediaCodec.BUFFER_FLAG_END_OF_STREAM) != 0;
                    decoder.releaseOutputBuffer(outIdx, decOutInfo.size > 0);
                    if (eos) {
                        encoder.signalEndOfInputStream();
                        decoderDoneOutput = true;
                    }
                }
            }

            // 3) drain the encoder: write its compressed output into the muxer
            int encIdx = encoder.dequeueOutputBuffer(encOutInfo, DEQUEUE_TIMEOUT_US);
            if (encIdx == MediaCodec.INFO_OUTPUT_FORMAT_CHANGED) {
                if (muxerStarted) return false; // the format must not change a second time
                muxerVideoTrack = muxer.addTrack(encoder.getOutputFormat());
                if (audioTrack >= 0 && audioFormat != null) muxerAudioTrack = muxer.addTrack(audioFormat);
                muxer.start();
                muxerStarted = true;
                for (Object[] p : pendingAudio) muxer.writeSampleData(muxerAudioTrack, (ByteBuffer) p[0], (MediaCodec.BufferInfo) p[1]);
                pendingAudio.clear();
            } else if (encIdx >= 0) {
                ByteBuffer encoded = encoder.getOutputBuffer(encIdx);
                if ((encOutInfo.flags & MediaCodec.BUFFER_FLAG_CODEC_CONFIG) != 0) {
                    encOutInfo.size = 0; // already captured by addTrack's format; writing it again would be redundant
                }
                if (encoded != null && encOutInfo.size > 0 && muxerStarted) {
                    encoded.position(encOutInfo.offset);
                    encoded.limit(encOutInfo.offset + encOutInfo.size);
                    muxer.writeSampleData(muxerVideoTrack, encoded, encOutInfo);
                }
                boolean eos = (encOutInfo.flags & MediaCodec.BUFFER_FLAG_END_OF_STREAM) != 0;
                encoder.releaseOutputBuffer(encIdx, false);
                if (eos) encoderDoneOutput = true;
            }
        }
        return muxerStarted;
    }

    private static void safeDelete(File f) {
        if (f != null) { try { f.delete(); } catch (Exception ignored) { } }
    }
}
