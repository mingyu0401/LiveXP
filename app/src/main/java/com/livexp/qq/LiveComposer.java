package com.livexp.qq;

import android.content.ContentResolver;
import android.content.ContentValues;
import android.content.Context;
import android.graphics.Bitmap;
import android.graphics.BitmapFactory;
import android.graphics.Canvas;
import android.graphics.Matrix;
import android.graphics.Paint;
import android.graphics.Rect;
import android.media.MediaCodec;
import android.media.MediaCodecInfo;
import android.media.MediaCodecList;
import android.media.MediaExtractor;
import android.media.MediaFormat;
import android.media.MediaMuxer;
import android.os.Handler;
import android.os.Looper;
import android.provider.MediaStore;

import java.io.File;
import java.nio.ByteBuffer;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

/**
 * 实况图 → 「动态段先播 + 收尾 3 秒定格照片」MP4 合成。
 *
 * 单趟编码:动态帧与定格帧都写进同一条 H.264 轨,时间戳由本类统一排。
 * 不做"两段各自编码再拼接",因为那会带上 SPS/PPS 不一致的风险。
 * 只用系统 API(MediaExtractor / MediaCodec / MediaMuxer / Bitmap / Canvas)。
 * 全部工作在 {@link #EXEC} 后台线程,主线程只收回调。
 */
public final class LiveComposer {

    /** 产物目录:QQ 自己的外部数据目录,QQ 读写无碍,且不在媒体扫描范围内(不会进相册)。 */
    public static final File TMP_DIR =
            new File("/sdcard/Android/data/com.tencent.mobileqq/LiveXP/send_tmp");

    static final long STILL_US = 3_000_000L;
    /** 动态段目标时长:内嵌视频不足时循环补足到这个长度,复刻相册实况回放的观感。 */
    private static final long MOTION_TARGET_US = 2_500_000L;
    private static final int MAX_MOTION_LOOPS = 4;
    private static final int FPS = 30;
    private static final int MAX_EDGE = 1920;
    private static final int MAX_MOTION_FRAMES = 1200;

    public static final int OK = 0;
    public static final int NOT_LIVE = 1;
    public static final int FAILED = 2;

    private static final ExecutorService EXEC = Executors.newSingleThreadExecutor();
    private static final Handler MAIN = new Handler(Looper.getMainLooper());

    private LiveComposer() {}

    public static final class Out {
        public File file;
        public long durationUs;
        public int width;
        public int height;
        public boolean hasAudio;
        public String md5;
        /** 发布到 MediaStore 后的行地址;QQ 的视频发送通道按 _id/媒体库路径取文件。 */
        public android.net.Uri storeUri;
        public long storeId = -1;
        public String storePath;
    }

    public interface Callback {
        /** status 为 {@link #OK}/{@link #NOT_LIVE}/{@link #FAILED};非 OK 一律按"发原图"处理。 */
        void onResult(int status, String srcPath, Out out, String error);
    }

    public static void composeAsync(final String srcPath, final Callback cb) {
        EXEC.execute(() -> {
            int status;
            String err = null;
            Out out = null;
            try {
                if (!LivePhotoDetector.isLiveFile(srcPath)) {
                    status = NOT_LIVE;
                } else {
                    out = new Out();
                    compose(srcPath, out);
                    publish(out);
                    status = OK;
                }
            } catch (Throwable t) {
                status = FAILED;
                err = t.getClass().getSimpleName() + ": " + t.getMessage();
                Log.i("compose failed src=" + srcPath + " err=" + err);
                if (out != null) deleteQuietly(out.file);
                out = null;
            }
            Log.i("compose result src=" + srcPath + " status=" + status
                    + (out == null ? "" : " out=" + out.file + " " + out.width + "x" + out.height
                    + " dur=" + (out.durationUs / 1000) + "ms audio=" + out.hasAudio));
            final Out fo = out;
            final String fe = err;
            final int fs = status;
            MAIN.post(() -> cb.onResult(fs, srcPath, fo, fe));
        });
    }

    // ------------------------------------------------------------------ 主流程

    private static void compose(String srcPath, Out out) throws Exception {
        File motion = LiveVideoPlayer.extract(srcPath);
        int[] img = boundsOf(srcPath);

        MediaExtractor vx = new MediaExtractor();
        MediaCodec enc = null;
        MediaCodec dec = null;
        MediaMuxer muxer = null;
        File tmp = null;
        boolean ok = false;
        EncSink sink = null;
        try {
            vx.setDataSource(motion.getAbsolutePath());
            int vTrack = pickTrack(vx, "video/");
            if (vTrack < 0) throw new IllegalStateException("动态段没有视频轨");
            MediaFormat vf = vx.getTrackFormat(vTrack);
            String decMime = vf.getString(MediaFormat.KEY_MIME);
            int codedW = vf.getInteger(MediaFormat.KEY_WIDTH);
            int codedH = vf.getInteger(MediaFormat.KEY_HEIGHT);
            int rot = normRot(intOr(vf, MediaFormat.KEY_ROTATION, 0));
            int cropL = intOr(vf, MediaFormat.KEY_CROP_LEFT, 0);
            int cropT = intOr(vf, MediaFormat.KEY_CROP_TOP, 0);
            int cropR = intOr(vf, MediaFormat.KEY_CROP_RIGHT, codedW - 1);
            int cropB = intOr(vf, MediaFormat.KEY_CROP_BOTTOM, codedH - 1);
            int dispW = Math.max(2, cropR - cropL + 1);
            int dispH = Math.max(2, cropB - cropT + 1);
            boolean swap = rot == 90 || rot == 270;
            int videoW = swap ? dispH : dispW;
            int videoH = swap ? dispW : dispH;
            int aTrack = pickTrack(vx, "audio/");
            MediaFormat af = aTrack >= 0 ? vx.getTrackFormat(aTrack) : null;

            int scale = pickScale(videoW, videoH, img[0], img[1]);
            int[] dims = alignSize(videoW / scale, videoH / scale);
            out.width = dims[0];
            out.height = dims[1];
            int exif = exifRotation(srcPath);
            Log.i("compose geom exif=" + exif + " videoRot=" + rot + " coded=" + codedW + "x"
                    + codedH + " crop=" + cropL + "," + cropT + "," + cropR + "," + cropB
                    + " disp=" + videoW + "x" + videoH + " imgBounds=" + img[0] + "x" + img[1]
                    + " out=" + dims[0] + "x" + dims[1]);

            MediaFormat ef = MediaFormat.createVideoFormat(MediaFormat.MIMETYPE_VIDEO_AVC,
                    dims[0], dims[1]);
            ef.setInteger(MediaFormat.KEY_COLOR_FORMAT, pickEncoderColorFormat());
            ef.setInteger(MediaFormat.KEY_FRAME_RATE, FPS);
            ef.setInteger(MediaFormat.KEY_BIT_RATE, bitrateFor(dims[0], dims[1]));
            // 定格段是静止画面,周期性 I 帧只会把码率预算吃光(首帧 I 帧不够大 → 定格发糊),
            // 所以放宽到覆盖整条视频,交界处的关键帧由下面的 SYNC 标志补。
            ef.setFloat(MediaFormat.KEY_I_FRAME_INTERVAL, 5.0f);
            // VBR:CBR 会把首帧 I 帧压到"平均每秒 30 份"的预算,一张 2.2MP 照片不够用,
            // 定格段开头会糊、QQ 气泡封面跟着糊。
            try {
                ef.setInteger(MediaFormat.KEY_BITRATE_MODE,
                        MediaCodecInfo.EncoderCapabilities.BITRATE_MODE_VBR);
            } catch (Throwable t) {
                Log.i("码控模式设置失败,用编码器默认: " + t);
            }

            enc = MediaCodec.createEncoderByType(MediaFormat.MIMETYPE_VIDEO_AVC);
            enc.configure(ef, null, (android.media.MediaCrypto) null,
                    MediaCodec.CONFIGURE_FLAG_ENCODE);
            enc.start();

            if (!TMP_DIR.isDirectory() && !TMP_DIR.mkdirs()) {
                throw new IllegalStateException("临时目录创建失败");
            }
            tmp = new File(TMP_DIR, "livexp_" + System.currentTimeMillis() + ".mp4");
            muxer = new MediaMuxer(tmp.getAbsolutePath(),
                    MediaMuxer.OutputFormat.MUXER_OUTPUT_MPEG_4);
            sink = new EncSink(enc, muxer, af);

            byte[] y = new byte[dims[0] * dims[1]];
            byte[] u = new byte[(dims[0] / 2) * (dims[1] / 2)];
            byte[] v = new byte[(dims[0] / 2) * (dims[1] / 2)];

            long frameUs = 1_000_000L / FPS;
            int stillFrames = (int) (STILL_US / frameUs);

            // ---- 段 1:动态视频轨(实况的动态部分先播)
            // OPPO 实况的内嵌视频普遍只有 0.8~1.2s,但相册回放观感有两三秒(循环播)。
            // 动态段不足 MOTION_TARGET_US 时从头循环补足,复刻相册观感。
            vx.selectTrack(vTrack);
            dec = MediaCodec.createDecoderByType(decMime);
            dec.configure(vf, null, null, 0);
            dec.start();
            byte[] mY = new byte[codedW * codedH];
            byte[] mU = new byte[(codedW / 2) * (codedH / 2)];
            byte[] mV = new byte[(codedW / 2) * (codedH / 2)];
            MediaCodec.BufferInfo bi = new MediaCodec.BufferInfo();
            long lastPts = 0;
            int frames = 0;
            int loops = 0;
            long deadline = System.currentTimeMillis() + 150_000L;
            while (frames < MAX_MOTION_FRAMES && System.currentTimeMillis() < deadline) {
                vx.seekTo(0, MediaExtractor.SEEK_TO_PREVIOUS_SYNC);
                long firstPts = Long.MIN_VALUE;
                long passEnd = lastPts;
                boolean eos = false;
                while (!eos && System.currentTimeMillis() < deadline) {
                    int ii = dec.dequeueInputBuffer(10_000);
                    if (ii >= 0) {
                        ByteBuffer in = dec.getInputBuffer(ii);
                        int n = in == null ? -1 : vx.readSampleData(in, 0);
                        if (n < 0) {
                            dec.queueInputBuffer(ii, 0, 0, 0, MediaCodec.BUFFER_FLAG_END_OF_STREAM);
                        } else {
                            in.position(0);
                            in.limit(n);
                            dec.queueInputBuffer(ii, 0, n, vx.getSampleTime(), 0);
                            vx.advance();
                        }
                    }
                    int oi = dec.dequeueOutputBuffer(bi, 10_000);
                    if (oi < 0) continue;
                    boolean config = (bi.flags & MediaCodec.BUFFER_FLAG_CODEC_CONFIG) != 0;
                    if (!config && bi.size > 0) {
                        android.media.Image im = dec.getOutputImage(oi);
                        if (im != null) {
                            unpack(im, mY, mU, mV, codedW, codedH);
                            blit(mY, mU, mV, codedW, codedH, cropL, cropT, cropR, cropB,
                                    rot, scale, y, u, v, dims[0], dims[1]);
                            long pts = Math.max(0, bi.presentationTimeUs);
                            if (pts < firstPts || firstPts == Long.MIN_VALUE) firstPts = pts;
                            long outPts = pts - firstPts + lastPts;
                            if (outPts < lastPts) outPts = lastPts;
                            passEnd = outPts;
                            sink.write(y, u, v, dims[0], dims[1], outPts,
                                    frames == 0 ? MediaCodec.BUFFER_FLAG_SYNC_FRAME : 0);
                            frames++;
                        }
                    }
                    dec.releaseOutputBuffer(oi, false);
                    if ((bi.flags & MediaCodec.BUFFER_FLAG_END_OF_STREAM) != 0) eos = true;
                }
                if (frames == 0) throw new IllegalStateException("动态段一帧都没解出来");
                lastPts = passEnd;
                boolean needMore = lastPts + frameUs < MOTION_TARGET_US
                        && loops + 1 < MAX_MOTION_LOOPS;
                if (!needMore) break;
                // 循环重播:解码器 flush 后从关键帧重新起播
                dec.flush();
                loops++;
                Log.i("motion loop " + loops + ": 段内累计 "
                        + ((lastPts + frameUs) / 1000) + "ms,继续补足");
            }
            if (loops > 0) {
                Log.i("motion loops total=" + loops + " 动态段=" + ((lastPts + frameUs) / 1000)
                        + "ms,音频只覆盖第一遍");
            }
            long motionEndUs = lastPts + frameUs;

            // ---- 段 2:定格画面(动态播完后停在照片上)
            StillFrame still = buildStill(srcPath, dims[0], dims[1], exif);
            for (int i = 0; i < stillFrames; i++) {
                sink.write(still.y, still.u, still.v, dims[0], dims[1], motionEndUs + i * frameUs,
                        i == 0 ? MediaCodec.BUFFER_FLAG_SYNC_FRAME : 0);
            }
            out.durationUs = motionEndUs + stillFrames * frameUs;
            sink.finish();
            if (af != null && sink.audioTrack >= 0) {
                // 音频必须和动态段同一条时间线:以前整体后移到定格段,
                // 会在音频轨前留下 3 秒空编辑,播放器(如 QQ)会直接跳到音频起点,看起来"前三秒被快速跳过"。
                out.hasAudio = remuxAudio(vx, vTrack, aTrack, muxer, sink.audioTrack, motionEndUs);
            }
            out.file = tmp;
            ok = true;
        } finally {
            closeQuietly(enc);
            closeQuietly(dec);
            if (muxer != null) {
                try {
                    muxer.stop();
                } catch (Throwable t) {
                    Log.i("muxer stop: " + t);
                    ok = false;
                }
                try {
                    muxer.release();
                } catch (Throwable ignored) {
                }
            }
            try {
                vx.release();
            } catch (Throwable ignored) {
            }
            if (!ok && tmp != null) deleteQuietly(tmp);
        }
        if (tmp == null || !tmp.isFile() || tmp.length() < 1024) {
            throw new IllegalStateException("产物缺失或过小");
        }
        out.file = tmp;
        out.md5 = LivePhotoDetector.md5OfFile(tmp.getAbsolutePath());
    }

    // ------------------------------------------------------------- 编码器写出

    /** 输入 packed I420 帧,输出写进 muxer。视频轨与音频轨都在首次格式变化时一起登记。 */
    private static final class EncSink {
        private final MediaCodec enc;
        private final MediaMuxer muxer;
        private final MediaFormat audioFormat;
        private int videoTrack = -1;
        int audioTrack = -1;
        private final MediaCodec.BufferInfo info = new MediaCodec.BufferInfo();

        EncSink(MediaCodec enc, MediaMuxer muxer, MediaFormat audioFormat) {
            this.enc = enc;
            this.muxer = muxer;
            this.audioFormat = audioFormat;
        }

        void write(byte[] y, byte[] u, byte[] v, int w, int h, long ptsUs, int flags) {
            int idx = enc.dequeueInputBuffer(50_000);
            if (idx < 0) {
                pump();
                idx = enc.dequeueInputBuffer(50_000);
            }
            if (idx < 0) throw new IllegalStateException("编码器输入缓冲拿不到");
            android.media.Image img = enc.getInputImage(idx);
            int size;
            if (img != null) {
                pack(img, y, u, v, w, h);
                size = rawFrameSize(img, w, h);
            } else {
                ByteBuffer b = enc.getInputBuffer(idx);
                if (b == null) throw new IllegalStateException("编码器输入缓冲为 null");
                b.clear();
                b.put(y);
                b.put(u);
                b.put(v);
                size = y.length + u.length + v.length;
            }
            enc.queueInputBuffer(idx, 0, size, ptsUs, flags);
            pump();
        }

        void finish() {
            int idx = enc.dequeueInputBuffer(100_000);
            if (idx >= 0) {
                enc.queueInputBuffer(idx, 0, 0, 0, MediaCodec.BUFFER_FLAG_END_OF_STREAM);
            }
            long deadline = System.currentTimeMillis() + 30_000L;
            while (System.currentTimeMillis() < deadline) {
                int oi = enc.dequeueOutputBuffer(info, 20_000);
                if (oi == MediaCodec.INFO_TRY_AGAIN_LATER) continue;
                if (oi == MediaCodec.INFO_OUTPUT_FORMAT_CHANGED) {
                    openTracks();
                    continue;
                }
                if (oi < 0) continue;
                emit(oi);
                if ((info.flags & MediaCodec.BUFFER_FLAG_END_OF_STREAM) != 0) break;
            }
            if (videoTrack < 0) throw new IllegalStateException("编码器没有输出");
        }

        private void pump() {
            for (int spin = 0; spin < 128; spin++) {
                int oi = enc.dequeueOutputBuffer(info, 10_000);
                if (oi == MediaCodec.INFO_TRY_AGAIN_LATER) return;
                if (oi == MediaCodec.INFO_OUTPUT_FORMAT_CHANGED) {
                    if (videoTrack < 0) openTracks();
                    continue;
                }
                if (oi < 0) continue;
                emit(oi);
            }
        }

        private void openTracks() {
            if (videoTrack >= 0) return;
            videoTrack = muxer.addTrack(enc.getOutputFormat());
            if (audioFormat != null) {
                try {
                    audioTrack = muxer.addTrack(audioFormat);
                } catch (Throwable t) {
                    audioTrack = -1;
                    Log.i("audio track rejected: " + t);
                }
            }
            muxer.start();
        }

        private void emit(int oi) {
            ByteBuffer buf = enc.getOutputBuffer(oi);
            if (buf != null && videoTrack >= 0 && info.size > 0) {
                buf.position(info.offset);
                buf.limit(info.offset + info.size);
                muxer.writeSampleData(videoTrack, buf, info);
            }
            enc.releaseOutputBuffer(oi, false);
        }
    }

    /** 音频不重编码,整条 remux,时间戳整体后移到动态段之后。 */
    /** 音频不重编码,按原生时间戳 remux;只保留动态段(limitUs)以内的样本。 */
    private static boolean remuxAudio(MediaExtractor ex, int videoTrack, int track, MediaMuxer muxer,
                                      int idx, long limitUs) {
        try {
            ex.unselectTrack(videoTrack);
            ex.selectTrack(track);
            ex.seekTo(0, MediaExtractor.SEEK_TO_PREVIOUS_SYNC);
            MediaCodec.BufferInfo bi = new MediaCodec.BufferInfo();
            ByteBuffer buf = ByteBuffer.allocate(256 * 1024);
            int n;
            int written = 0;
            while ((n = ex.readSampleData(buf, 0)) >= 0) {
                long t = Math.max(0, ex.getSampleTime());
                if (t >= limitUs) break;
                buf.position(0);
                buf.limit(n);
                bi.offset = 0;
                bi.size = n;
                bi.presentationTimeUs = t;
                bi.flags = 0;
                muxer.writeSampleData(idx, buf, bi);
                written++;
                ex.advance();
            }
            return written > 0;
        } catch (Throwable t) {
            Log.i("audio remux failed: " + t);
            return false;
        }
    }

    // ------------------------------------------------------------- 尺寸与格式

    /** 整数缩小倍数:边长不超过 MAX_EDGE,且不超过原图尺寸。 */
    private static int pickScale(int videoW, int videoH, int imgW, int imgH) {
        int s = 1;
        s = Math.max(s, (Math.max(videoW, videoH) + MAX_EDGE - 1) / MAX_EDGE);
        if (imgW > 0 && imgH > 0) {
            s = Math.max(s, (videoW + imgW - 1) / imgW);
            s = Math.max(s, (videoH + imgH - 1) / imgH);
        }
        return Math.max(1, s);
    }

    /** 按编码器对齐要求把尺寸向下取(至少 2 的倍数)。 */
    private static int[] alignSize(int w, int h) {
        int aw = 2;
        int ah = 2;
        try {
            MediaCodecInfo ci = encoderInfo(MediaFormat.MIMETYPE_VIDEO_AVC);
            MediaCodecInfo.VideoCapabilities vc =
                    ci.getCapabilitiesForType(MediaFormat.MIMETYPE_VIDEO_AVC).getVideoCapabilities();
            if (vc != null) {
                aw = Math.max(aw, (int) vc.getWidthAlignment());
                ah = Math.max(ah, (int) vc.getHeightAlignment());
            }
        } catch (Throwable t) {
            Log.i("encoder alignment probe failed, use 2: " + t);
        }
        int ow = Math.max(aw, (w / aw) * aw);
        int oh = Math.max(ah, (h / ah) * ah);
        return new int[]{ow, oh};
    }

    private static volatile MediaCodecInfo cachedEncoder;

    private static MediaCodecInfo encoderInfo(String mime) {
        MediaCodecInfo hit = cachedEncoder;
        if (hit != null) return hit;
        MediaCodecList list = new MediaCodecList(MediaCodecList.REGULAR_CODECS);
        for (MediaCodecInfo ci : list.getCodecInfos()) {
            if (ci.isEncoder()) continue;
            for (String t : ci.getSupportedTypes()) {
                if (t.equalsIgnoreCase(mime)) return ci;
            }
        }
        for (MediaCodecInfo ci : list.getCodecInfos()) {
            if (!ci.isEncoder()) continue;
            for (String t : ci.getSupportedTypes()) {
                if (t.equalsIgnoreCase(mime)) {
                    cachedEncoder = ci;
                    return ci;
                }
            }
        }
        throw new IllegalStateException("没有 " + mime + " 编码器");
    }

    private static int pickEncoderColorFormat() {
        int[] prefer = {MediaCodecInfo.CodecCapabilities.COLOR_FormatYUV420Planar,
                MediaCodecInfo.CodecCapabilities.COLOR_FormatYUV420SemiPlanar,
                MediaCodecInfo.CodecCapabilities.COLOR_FormatYUV420Flexible};
        try {
            int[] supported = encoderInfo(MediaFormat.MIMETYPE_VIDEO_AVC)
                    .getCapabilitiesForType(MediaFormat.MIMETYPE_VIDEO_AVC).colorFormats;
            for (int want : prefer) {
                for (int has : supported) {
                    if (has == want) return want;
                }
            }
        } catch (Throwable t) {
            Log.i("color format probe failed: " + t);
        }
        return MediaCodecInfo.CodecCapabilities.COLOR_FormatYUV420Planar;
    }

    private static int bitrateFor(int w, int h) {
        long px = (long) w * h;
        return (int) Math.min(12_000_000L, Math.max(2_500_000L, px * FPS / 150));
    }

    private static int normRot(int r) {
        r = ((r % 360) + 360) % 360;
        return r == 90 || r == 180 || r == 270 ? r : 0;
    }

    private static int pickTrack(MediaExtractor ex, String prefix) {
        for (int i = 0; i < ex.getTrackCount(); i++) {
            String m = ex.getTrackFormat(i).getString(MediaFormat.KEY_MIME);
            if (m != null && m.startsWith(prefix)) return i;
        }
        return -1;
    }

    private static int intOr(MediaFormat f, String key, int def) {
        try {
            return f.containsKey(key) ? f.getInteger(key) : def;
        } catch (Throwable t) {
            return def;
        }
    }

    /** 原图显示尺寸(含 EXIF 方向),用于"分辨率不超过原图"的约束。 */
    private static int[] boundsOf(String path) {
        BitmapFactory.Options o = new BitmapFactory.Options();
        o.inJustDecodeBounds = true;
        try {
            BitmapFactory.decodeFile(path, o);
        } catch (Throwable ignored) {
        }
        int w = Math.max(1, o.outWidth);
        int h = Math.max(1, o.outHeight);
        int exif = exifRotation(path);
        if (exif == 90 || exif == -90 || exif == 270 || exif == -270) {
            int t = w;
            w = h;
            h = t;
        }
        return new int[]{w, h};
    }

    // ------------------------------------------------------------- 定格帧

    private static final class StillFrame {
        byte[] y;
        byte[] u;
        byte[] v;
    }

    /**
     * 封面图按动态段显示尺寸居中裁剪,再转 I420。
     * 方向取 JPEG 自己的 EXIF,不能套用动态段的旋转:实况照片与它的内嵌视频各自记录方向,
     * 混用会把定格帧转 90 度并因比例失配被迫放大,画面发糊。
     */
    private static StillFrame buildStill(String jpg, int w, int h, int exif) throws Exception {
        BitmapFactory.Options bound = new BitmapFactory.Options();
        bound.inJustDecodeBounds = true;
        BitmapFactory.decodeFile(jpg, bound);
        int sw = bound.outWidth, sh = bound.outHeight;
        int exifSw = sw, exifSh = sh;
        if (exif == 90 || exif == 270) {
            int t = sw;
            sw = sh;
            sh = t;
        }
        if (sw <= 0 || sh <= 0) throw new IllegalStateException("封面图读不出尺寸");
        int sample = 1;
        while (sw / (sample * 2) >= w && sh / (sample * 2) >= h) sample *= 2;
        BitmapFactory.Options opt = new BitmapFactory.Options();
        opt.inSampleSize = sample;
        opt.inPreferredConfig = Bitmap.Config.ARGB_8888;
        Bitmap raw = BitmapFactory.decodeFile(jpg, opt);
        if (raw == null) throw new IllegalStateException("封面图解码失败");
        Log.i("still decode bounds=" + exifSw + "x" + exifSh + " exif=" + exif
                + " bitmap=" + raw.getWidth() + "x" + raw.getHeight());
        Bitmap rotated = null;
        Bitmap cover = null;
        try {
            Matrix m = new Matrix();
            if (exif == 90 || exif == 180 || exif == 270) m.postRotate(exif);
            else if (exif == -90) { m.postScale(-1, 1); m.postRotate(90); }   // 水平镜像 + 旋转
            else if (exif == -180) m.postScale(-1, -1);
            else if (exif == -270) { m.postScale(-1, 1); m.postRotate(-90); }
            rotated = exif == 0 ? raw
                    : Bitmap.createBitmap(raw, 0, 0, raw.getWidth(), raw.getHeight(), m, true);
            int rw = rotated.getWidth(), rh = rotated.getHeight();
            double srcRatio = (double) rw / rh;
            double dstRatio = (double) w / h;
            int cropW, cropH;
            if (srcRatio > dstRatio) {
                cropH = rh;
                cropW = Math.max(1, (int) Math.round(rh * dstRatio));
            } else {
                cropW = rw;
                cropH = Math.max(1, (int) Math.round(rw / dstRatio));
            }
            int cx = (rw - cropW) / 2, cy = (rh - cropH) / 2;
            cover = Bitmap.createBitmap(w, h, Bitmap.Config.ARGB_8888);
            Canvas c = new Canvas(cover);
            Paint p = new Paint(Paint.FILTER_BITMAP_FLAG);
            c.drawBitmap(rotated, new Rect(cx, cy, cx + cropW, cy + cropH),
                    new Rect(0, 0, w, h), p);
            int[] px = new int[w * h];
            cover.getPixels(px, 0, w, 0, 0, w, h);
            StillFrame s = new StillFrame();
            rgbToI420(px, s, w, h);
            return s;
        } finally {
            raw.recycle();
            if (rotated != null && rotated != raw) rotated.recycle();
            if (cover != null) cover.recycle();
        }
    }

    /** EXIF Orientation → 需要施加的旋转角(正数=顺时针角度,负数=镜像组合,0=原样)。 */
    private static int exifRotation(String jpg) {
        try {
            android.media.ExifInterface ex = new android.media.ExifInterface(jpg);
            switch (ex.getAttributeInt(android.media.ExifInterface.TAG_ORIENTATION,
                    android.media.ExifInterface.ORIENTATION_UNDEFINED)) {
                case android.media.ExifInterface.ORIENTATION_ROTATE_90: return 90;
                case android.media.ExifInterface.ORIENTATION_ROTATE_180: return 180;
                case android.media.ExifInterface.ORIENTATION_ROTATE_270: return 270;
                case android.media.ExifInterface.ORIENTATION_TRANSPOSE: return -90;
                case android.media.ExifInterface.ORIENTATION_TRANSVERSE: return -270;
                case android.media.ExifInterface.ORIENTATION_FLIP_VERTICAL: return -180;
                default: return 0;
            }
        } catch (Throwable t) {
            Log.i("EXIF 方向读取失败,按原样处理: " + t);
            return 0;
        }
    }

    /** BT.601 限定域 ARGB → I420(2x2 块平均)。 */
    private static void rgbToI420(int[] argb, StillFrame s, int w, int h) {
        s.y = new byte[w * h];
        s.u = new byte[(w / 2) * (h / 2)];
        s.v = new byte[(w / 2) * (h / 2)];
        for (int j = 0; j < h; j++) {
            int row = j * w;
            for (int i = 0; i < w; i++) {
                int p = argb[row + i];
                int r = (p >> 16) & 0xFF, g = (p >> 8) & 0xFF, b = p & 0xFF;
                int yy = ((66 * r + 129 * g + 25 * b + 128) >> 8) + 16;
                s.y[row + i] = (byte) clamp(yy);
            }
        }
        for (int j = 0; j < h / 2; j++) {
            for (int i = 0; i < w / 2; i++) {
                int r = 0, g = 0, b = 0;
                for (int dy = 0; dy < 2; dy++) {
                    for (int dx = 0; dx < 2; dx++) {
                        int p = argb[(j * 2 + dy) * w + i * 2 + dx];
                        r += (p >> 16) & 0xFF;
                        g += (p >> 8) & 0xFF;
                        b += p & 0xFF;
                    }
                }
                r >>= 2;
                g >>= 2;
                b >>= 2;
                int uu = ((-38 * r - 74 * g + 112 * b + 128) >> 8) + 128;
                int vv = ((112 * r - 94 * g - 18 * b + 128) >> 8) + 128;
                s.u[j * (w / 2) + i] = (byte) clamp(uu);
                s.v[j * (w / 2) + i] = (byte) clamp(vv);
            }
        }
    }

    private static int clamp(int x) {
        return x < 0 ? 0 : (x > 255 ? 255 : x);
    }

    // ------------------------------------------------------------- 平面搬运

    /** MediaCodec Image(1/2/3 平面,含 NV12 变体)→ packed I420。 */
    private static void unpack(android.media.Image img, byte[] y, byte[] u, byte[] v,
                               int w, int h) {
        android.media.Image.Plane[] pl = img.getPlanes();
        int cwy = w / 2;
        copyPlane(pl[0].getBuffer(), pl[0].getRowStride(), 1, y, w, h);
        if (pl.length >= 3) {
            copyPlane(pl[1].getBuffer(), pl[1].getRowStride(), pl[1].getPixelStride(), u, cwy, h / 2);
            copyPlane(pl[2].getBuffer(), pl[2].getRowStride(), pl[2].getPixelStride(), v, cwy, h / 2);
            return;
        }
        ByteBuffer uv = pl[1].getBuffer();
        int stride = pl[1].getRowStride();
        int ps = Math.max(1, pl[1].getPixelStride());
        int limit = uv.limit();
        for (int j = 0; j < h / 2; j++) {
            for (int i = 0; i < cwy; i++) {
                int base = j * stride + i * ps;
                if (base + 1 >= limit) return;
                u[j * cwy + i] = uv.get(base);
                v[j * cwy + i] = uv.get(base + 1);
            }
        }
    }

    /** packed I420 → MediaCodec Image。 */
    private static void pack(android.media.Image img, byte[] y, byte[] u, byte[] v,
                             int w, int h) {
        android.media.Image.Plane[] pl = img.getPlanes();
        int cwy = w / 2;
        writePlane(pl[0].getBuffer(), pl[0].getRowStride(), 1, y, w, h);
        if (pl.length >= 3) {
            writePlane(pl[1].getBuffer(), pl[1].getRowStride(), pl[1].getPixelStride(), u, cwy, h / 2);
            writePlane(pl[2].getBuffer(), pl[2].getRowStride(), pl[2].getPixelStride(), v, cwy, h / 2);
            return;
        }
        ByteBuffer uv = pl[1].getBuffer();
        int stride = pl[1].getRowStride();
        int ps = Math.max(1, pl[1].getPixelStride());
        int limit = uv.capacity();
        for (int j = 0; j < h / 2; j++) {
            for (int i = 0; i < cwy; i++) {
                int base = j * stride + i * ps;
                if (base + 1 >= limit) return;
                uv.put(base, u[j * cwy + i]);
                uv.put(base + 1, v[j * cwy + i]);
            }
        }
    }

    private static void copyPlane(ByteBuffer src, int rowStride, int pixelStride,
                                  byte[] dst, int rowLen, int rows) {
        int limit = src.limit();
        for (int j = 0; j < rows; j++) {
            int sBase = j * rowStride;
            int dBase = j * rowLen;
            for (int i = 0; i < rowLen; i++) {
                int p = sBase + i * pixelStride;
                if (p >= limit) return;
                dst[dBase + i] = src.get(p);
            }
        }
    }

    private static void writePlane(ByteBuffer dst, int rowStride, int pixelStride,
                                   byte[] src, int rowLen, int rows) {
        int limit = dst.capacity();
        for (int j = 0; j < rows; j++) {
            int sBase = j * rowLen;
            int dBase = j * rowStride;
            for (int i = 0; i < rowLen; i++) {
                int p = dBase + i * pixelStride;
                if (p >= limit) return;
                dst.put(p, src[sBase + i]);
            }
        }
    }

    /** 一帧在 Image 布局下占用的字节数(queueInputBuffer 的 size)。 */
    private static int rawFrameSize(android.media.Image img, int w, int h) {
        int total = 0;
        android.media.Image.Plane[] pl = img.getPlanes();
        for (int i = 0; i < pl.length; i++) {
            int rowLen = (i == 0) ? w : w / 2;
            int rows = (i == 0) ? h : h / 2;
            int ps = Math.max(1, pl[i].getPixelStride());
            total += pl[i].getRowStride() * (rows - 1) + ps * rowLen;
        }
        return total;
    }

    /** 裁剪 + 旋转 + 整数最近邻缩小。 */
    private static void blit(byte[] sy, byte[] su, byte[] sv, int sw, int sh,
                             int cl, int ct, int cr, int cb, int rot, int scale,
                             byte[] dy, byte[] du, byte[] dv, int dw, int dh) {
        for (int row = 0; row < dh; row++) {
            int dBase = row * dw;
            for (int col = 0; col < dw; col++) {
                int s = srcIndex(cl, ct, cr, cb, rot, scale, col, row, sw);
                dy[dBase + col] = sy[s];
            }
        }
        int cwy = dw / 2;
        int scw = sw / 2;
        for (int row = 0; row < dh / 2; row++) {
            for (int col = 0; col < cwy; col++) {
                int si = chromaIndex(cl, ct, cr, cb, rot, scale, col, row, scw);
                if (si < 0 || si >= su.length) continue;
                du[row * cwy + col] = su[si];
                dv[row * cwy + col] = sv[si];
            }
        }
    }

    /** 色度平面的源下标:半分辨率坐标域里做同样的裁剪/旋转/缩放映射。 */
    private static int chromaIndex(int cl, int ct, int cr, int cb, int rot, int scale,
                                   int x, int y, int scw) {
        int hcl = cl / 2, hct = ct / 2, hcr = cr / 2, hcb = cb / 2;
        int sx;
        int sy;
        switch (rot) {
            case 90:
                sx = hcl + y * scale;
                sy = hcb - x * scale;
                break;
            case 180:
                sx = hcr - x * scale;
                sy = hcb - y * scale;
                break;
            case 270:
                sx = hcr - y * scale;
                sy = hct + x * scale;
                break;
            default:
                sx = hcl + x * scale;
                sy = hct + y * scale;
                break;
        }
        if (sx < hcl) sx = hcl;
        if (sx > hcr) sx = hcr;
        if (sy < hct) sy = hct;
        if (sy > hcb) sy = hcb;
        return sy * scw + sx;
    }

    /**
     * 目标(display 空间)像素 → 源(coded 空间)像素下标。
     * KEY_ROTATION 的约定是"把编码帧顺时针转这么多度才是正立",
     * 所以 90 度时 display 左上角取裁剪区的左下角 (cl, cb)。
     */
    private static int srcIndex(int cl, int ct, int cr, int cb, int rot, int scale,
                                int x, int y, int sw) {
        int sx;
        int syy;
        switch (rot) {
            case 90:
                sx = cl + y * scale;
                syy = cb - x * scale;
                break;
            case 180:
                sx = cr - x * scale;
                syy = cb - y * scale;
                break;
            case 270:
                sx = cr - y * scale;
                syy = ct + x * scale;
                break;
            default:
                sx = cl + x * scale;
                syy = ct + y * scale;
                break;
        }
        if (sx < cl) sx = cl;
        if (sx > cr) sx = cr;
        if (syy < ct) syy = ct;
        if (syy > cb) syy = cb;
        return syy * sw + sx;
    }

    // ------------------------------------------------------------- 媒体库发布

    /**
     * 把合成结果登记进 MediaStore(Video)并置 IS_PENDING=1,再把字节拷进去。
     * QQ 的视频发送通道是按媒体库取文件的(相册项自带 _id),裸文件路径它读不到;
     * IS_PENDING 期间只有宿主自己可见,不会出现在用户相册里。
     */
    private static void publish(Out out) throws Exception {
        Context ctx = HookEntry.appCtx;
        if (ctx == null) throw new IllegalStateException("宿主 Context 还没拿到");
        ContentResolver cr = ctx.getContentResolver();
        String name = out.file.getName();
        ContentValues cv = new ContentValues();
        cv.put(MediaStore.MediaColumns.DISPLAY_NAME, name);
        cv.put(MediaStore.MediaColumns.MIME_TYPE, "video/mp4");
        cv.put(MediaStore.MediaColumns.SIZE, out.file.length());
        cv.put(MediaStore.Video.Media.DURATION, out.durationUs / 1000L);
        cv.put(MediaStore.Video.Media.WIDTH, out.width);
        cv.put(MediaStore.Video.Media.HEIGHT, out.height);
        boolean scoped = android.os.Build.VERSION.SDK_INT >= 29;
        if (scoped) {
            cv.put(MediaStore.MediaColumns.RELATIVE_PATH, "Movies/LiveXP");
            cv.put(MediaStore.MediaColumns.IS_PENDING, 1);
        } else {
            cv.put(MediaStore.MediaColumns.DATA, new File(TMP_DIR, name).getAbsolutePath());
        }
        android.net.Uri uri = cr.insert(MediaStore.Video.Media.EXTERNAL_CONTENT_URI, cv);
        if (uri == null) throw new IllegalStateException("MediaStore insert 返回 null");
        try (java.io.OutputStream os = cr.openOutputStream(uri);
             java.io.InputStream is = new java.io.FileInputStream(out.file)) {
            if (os == null) throw new IllegalStateException("媒体库输出流打不开");
            byte[] buf = new byte[128 * 1024];
            int n;
            while ((n = is.read(buf)) > 0) os.write(buf, 0, n);
            os.flush();
        } catch (Throwable t) {
            try {
                cr.delete(uri, null, null);
            } catch (Throwable ignored) {
            }
            throw new IllegalStateException("写入媒体库失败: " + t);
        }
        long id = -1;
        String dataPath = null;
        android.database.Cursor c = cr.query(uri,
                new String[]{MediaStore.MediaColumns._ID, MediaStore.MediaColumns.DATA},
                null, null, null);
        if (c != null) {
            try {
                if (c.moveToFirst()) {
                    id = c.getLong(0);
                    dataPath = c.getString(1);
                }
            } finally {
                c.close();
            }
        }
        if (id < 0) {
            try {
                cr.delete(uri, null, null);
            } catch (Throwable ignored) {
            }
            throw new IllegalStateException("拿不到媒体库 _id");
        }
        if (dataPath == null) {
            dataPath = new File(android.os.Environment.getExternalStorageDirectory(),
                    "Movies/LiveXP/" + name).getAbsolutePath();
        }
        out.storeUri = uri;
        out.storeId = id;
        out.storePath = dataPath;
        Log.i("已发布到媒体库 id=" + id + " uri=" + uri + " path=" + dataPath);
    }

    /** 发送确认后清场:删媒体库行 + 删文件(私有目录那份 + 媒体库那份)。 */
    public static void discard(final Out out) {
        if (out == null) return;
        final android.net.Uri uri = out.storeUri;
        final String storePath = out.storePath;
        final File file = out.file;
        if (uri != null) {
            IO.execute(() -> {
                try {
                    Context ctx = HookEntry.appCtx;
                    if (ctx != null) ctx.getContentResolver().delete(uri, null, null);
                } catch (Throwable t) {
                    Log.i("媒体库行删除失败: " + t);
                }
                if (storePath != null) deleteQuietly(new File(storePath));
                deleteQuietly(file);
            });
        } else {
            deleteQuietly(file);
        }
    }

    private static final ExecutorService IO = Executors.newSingleThreadExecutor();

    // ------------------------------------------------------------- 杂项

    private static void closeQuietly(MediaCodec c) {
        if (c == null) return;
        try {
            c.stop();
        } catch (Throwable ignored) {
        }
        try {
            c.release();
        } catch (Throwable ignored) {
        }
    }

    static void deleteQuietly(File f) {
        try {
            if (f != null && f.isFile() && !f.delete()) Log.i("临时文件删除失败: " + f);
        } catch (Throwable ignored) {
        }
    }
}
