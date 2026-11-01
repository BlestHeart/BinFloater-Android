package com.binf.floater;

import android.content.Context;
import android.media.AudioAttributes;
import android.media.AudioFormat;
import android.media.AudioTrack;
import android.os.Build;
import android.os.Handler;
import android.os.Looper;
import android.os.VibrationEffect;
import android.os.Vibrator;
import android.os.VibratorManager;

/**
 * 把一段摩斯码「播」出来：音调 + 震动节拍，同时回调让界面跟着闪。
 *
 * <p>为什么要有它：摩斯码本来就是给耳朵听的东西。以前只能看着一串 . - 数，
 * 现在能真的听到「滴—嗒」；静音场合有震动，口袋里的手机也能敲出节奏。
 *
 * <p>音不是素材，是算出来的：44100Hz 单声道 16bit 正弦波，每段头尾各 5ms
 * 淡入淡出（不淡会「啪」一声，那是方波边沿）。所以这一路完全离线，
 * 不占安装包体积，也不需要任何音频授权。
 *
 * <p>播放不阻塞界面：整段节奏先由 {@link BinCore#morsePlan} 算成
 * 「发声, 静音, 发声, 静音…」的毫秒数组，工作线程按这个表走，
 * 音频写一块放一块（AudioTrack 阻塞写自带节拍）。
 */
public final class MorsePlayer {

    /** 播放进度回调。都在主线程上调，可以直接动 View。 */
    public interface Listener {
        /** 一段发声/静音开始了，用来闪指示。 */
        void onTone(boolean on);

        /** 播完了（正常结束或被 {@link #stop()} 打断）。 */
        void onFinished();
    }

    public static final int SAMPLE_RATE = 44100;
    /** 音调频率。600Hz 是摩斯练习常用的一个中频，听久了不刺耳。 */
    public static final int TONE_HZ = 600;
    /** 单次播放的时长上限：手机面板上没人要听三分钟摩斯。 */
    public static final int MAX_PLAY_MS = 60_000;
    /** 上下限之外的 WPM 没有实际意义，夹一下免得算出 1ms 的点。 */
    public static final int MIN_WPM = BinCore.MORSE_MIN_WPM;
    public static final int MAX_WPM = BinCore.MORSE_MAX_WPM;

    private static final int FADE_MS = 5;

    private final Context app;
    private final Handler main = new Handler(Looper.getMainLooper());
    private Thread worker;
    private volatile boolean stopRequested;
    private volatile boolean playing;
    private AudioTrack track;
    /** 上一次 {@link #start} 是不是因为太长被截断了（提示用）。 */
    private boolean lastTruncated;

    public MorsePlayer(Context context) {
        this.app = context.getApplicationContext();
    }

    public boolean isPlaying() {
        return playing;
    }

    /**
     * 开始播放。
     *
     * @param morse      摩斯码文本（字母空格、词用 /），不是摩斯码就别调它
     * @param wpm        速度
     * @param vibrate    要不要同时震动
     * @param listener   进度回调，可传 null
     * @return null 表示开始播放了；否则是给用户看的错误文案
     */
    public String start(String morse, int wpm, boolean vibrate, Listener listener) {
        stop();
        if (morse == null || morse.trim().isEmpty()) return "没有可播放的摩斯码";
        final BinCore.MorsePlan plan = BinCore.morsePlan(morse, wpm, MAX_PLAY_MS);
        if (plan.pattern.length == 0) return "没有可播放的摩斯码";

        final Listener l = listener;
        lastTruncated = plan.truncated;
        stopRequested = false;
        playing = true;
        final Vibrator vib = vibrate ? vibrator() : null;
        worker = new Thread(() -> run(plan, vib, l), "morse-player");
        worker.setDaemon(true);
        worker.start();
        return null;
    }

    /** 上一次播放是不是因为超过 {@link #MAX_PLAY_MS} 被截断了。 */
    public boolean lastTruncated() {
        return lastTruncated;
    }

    /** 停。可以从任意线程调；正在播的话回调 onFinished。 */
    public void stop() {
        stopRequested = true;
        Thread t = worker;
        if (t != null) t.interrupt();
        AudioTrack at = track;
        track = null;
        if (at != null) {
            try {
                at.pause();
                at.flush();
            } catch (Throwable ignored) {
            }
            try {
                at.release();
            } catch (Throwable ignored) {
            }
        }
        // 主动 stop() 不回调 onFinished：调用方自己把按钮改回去，
        // 别再触发一次「播放结束」逻辑导致状态打架。
        playing = false;
        worker = null;
    }

    // ---------- 内部 ----------

    private Vibrator vibrator() {
        try {
            if (Build.VERSION.SDK_INT >= 31) {
                VibratorManager vm = (VibratorManager) app.getSystemService(Context.VIBRATOR_MANAGER_SERVICE);
                return vm == null ? null : vm.getDefaultVibrator();
            }
            return (Vibrator) app.getSystemService(Context.VIBRATOR_SERVICE);
        } catch (Throwable t) {
            return null;
        }
    }

    private void run(BinCore.MorsePlan plan, Vibrator vib, Listener l) {
        AudioTrack at = null;
        try {
            at = newTrack();
            track = at;
            at.play();
            int[] p = plan.pattern;
            for (int i = 0; i < p.length; i++) {
                if (stopRequested) break;
                int ms = p[i];
                if (ms <= 0) continue;
                boolean tone = (i % 2 == 0);
                if (l != null) {
                    final boolean on = tone;
                    main.post(() -> l.onTone(on));
                }
                if (tone && vib != null) {
                    try {
                        vib.vibrate(VibrationEffect.createOneShot(ms,
                                VibrationEffect.DEFAULT_AMPLITUDE));
                    } catch (Throwable ignored) {
                    }
                }
                writeMillis(at, ms, tone);
            }
            if (at != null) {
                try {
                    at.stop();
                } catch (Throwable ignored) {
                }
            }
        } catch (Throwable ignored) {
        } finally {
            if (at != null) {
                try {
                    at.release();
                } catch (Throwable ignored) {
                }
            }
            if (track == at) track = null;
            boolean interrupted = stopRequested;
            stopRequested = true;
            playing = false;
            worker = null;
            if (vib != null) {
                try {
                    vib.cancel();
                } catch (Throwable ignored) {
                }
            }
            if (l != null) {
                main.post(() -> {
                    l.onTone(false);
                    if (!interrupted) l.onFinished();
                });
            }
        }
    }

    private AudioTrack newTrack() {
        int minBuf = AudioTrack.getMinBufferSize(SAMPLE_RATE,
                AudioFormat.CHANNEL_OUT_MONO, AudioFormat.ENCODING_PCM_16BIT);
        int buf = Math.max(minBuf, SAMPLE_RATE / 2 * 2);   // 至少半秒，够平滑
        AudioAttributes attrs = new AudioAttributes.Builder()
                .setUsage(AudioAttributes.USAGE_MEDIA)
                .setContentType(AudioAttributes.CONTENT_TYPE_SONIFICATION)
                .build();
        AudioFormat fmt = new AudioFormat.Builder()
                .setEncoding(AudioFormat.ENCODING_PCM_16BIT)
                .setSampleRate(SAMPLE_RATE)
                .setChannelMask(AudioFormat.CHANNEL_OUT_MONO)
                .build();
        AudioTrack t = new AudioTrack.Builder()
                .setAudioAttributes(attrs)
                .setAudioFormat(fmt)
                .setBufferSizeInBytes(buf)
                .setTransferMode(AudioTrack.MODE_STREAM)
                .build();
        t.setVolume(0.6f);   // 摩斯的「滴」很尖，全音量太吵
        return t;
    }

    /** 写 {@code ms} 毫秒的样本：tone=true 是正弦波，false 是静音。 */
    private void writeMillis(AudioTrack at, int ms, boolean tone) {
        if (at == null) {
            // 没有音频输出（比如极少数机型建不出来）：光靠节拍也别把整体拖慢
            sleepQuietly(ms);
            return;
        }
        int total = (int) ((long) SAMPLE_RATE * ms / 1000L);
        int fade = Math.min(total / 2, SAMPLE_RATE * FADE_MS / 1000);
        short[] buf = new short[Math.min(total <= 0 ? 1 : total, 1024)];
        double step = 2.0 * Math.PI * TONE_HZ / SAMPLE_RATE;
        int done = 0;
        while (done < total) {
            if (stopRequested) return;
            int n = Math.min(buf.length, total - done);
            for (int k = 0; k < n; k++) {
                int idx = done + k;
                double env = 1.0;
                if (tone && fade > 0) {
                    if (idx < fade) env = (double) idx / fade;
                    else if (idx >= total - fade) env = (double) (total - 1 - idx) / fade;
                }
                buf[k] = tone
                        ? (short) (Math.sin(step * idx) * env * 12000.0)
                        : (short) 0;
            }
            int written = at.write(buf, 0, n);
            if (written < 0) return;
            done += n;
        }
    }

    private static void sleepQuietly(int ms) {
        try {
            Thread.sleep(Math.max(1, ms));
        } catch (InterruptedException ignored) {
            Thread.currentThread().interrupt();
        }
    }
}
