package client.nilore.modules.impl.misc.music;

import javax.sound.sampled.AudioFormat;
import javax.sound.sampled.AudioInputStream;
import javax.sound.sampled.AudioSystem;
import javax.sound.sampled.DataLine;
import javax.sound.sampled.FloatControl;
import javax.sound.sampled.SourceDataLine;
import java.io.BufferedInputStream;
import java.net.URI;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.util.concurrent.atomic.AtomicLong;
import java.util.concurrent.atomic.AtomicReference;

public class AudioPlayer {
    public enum State { STOPPED, PLAYING, PAUSED, LOADING }

    private final AtomicReference<State> state = new AtomicReference<>(State.STOPPED);
    private volatile float volume = 0.8f;
    private volatile SongInfo currentSong;
    private volatile String currentUrl;
    private volatile SourceDataLine currentLine;
    private volatile Thread playbackThread;
    private volatile boolean paused;

    private static final int SPECTRUM_BARS = 32;
    private static final int ANALYSIS_WINDOW = 1024;
    private final AtomicLong playbackGeneration = new AtomicLong();
    private final AtomicReference<float[]> spectrumSnapshot = new AtomicReference<>(new float[0]);
    private final float[] analysisSamples = new float[ANALYSIS_WINDOW];
    private int analysisSampleCount;

    // time-based progress tracking
    private volatile long playStartMs;
    private volatile long pauseStartMs;
    private volatile long totalPausedMs;

    // seek support
    private volatile long seekTargetMs = -1;
    private volatile long seekDisplayMs = -1;
    private volatile long bytesConsumedFromStream = 0;

    public void play(SongInfo song, String url) {
        play(song, url, null);
    }

    public void play(SongInfo song, String url, Runnable onNaturalEnd) {
        stop();
        long generation = playbackGeneration.incrementAndGet();
        this.currentSong = song;
        this.currentUrl = url;
        this.state.set(State.LOADING);
        this.paused = false;
        this.totalPausedMs = 0;
        this.playStartMs = System.currentTimeMillis();
        this.seekTargetMs = -1;
        this.seekDisplayMs = -1;
        playbackThread = new Thread(() -> playInternal(url, generation, song, onNaturalEnd), "MusicPlayer-Playback");
        playbackThread.setDaemon(true);
        playbackThread.start();
    }

    public void pause() {
        if (state.get() == State.PLAYING && currentLine != null) {
            paused = true;
            pauseStartMs = System.currentTimeMillis();
            currentLine.stop();
            state.set(State.PAUSED);
        }
    }

    public void resume() {
        if (state.get() == State.PAUSED && currentLine != null) {
            paused = false;
            totalPausedMs += System.currentTimeMillis() - pauseStartMs;
            currentLine.start();
            state.set(State.PLAYING);
        }
    }

    public void stop() {
        playbackGeneration.incrementAndGet();
        spectrumSnapshot.set(new float[0]);
        analysisSampleCount = 0;
        state.set(State.STOPPED);
        paused = false;
        seekTargetMs = -1;
        seekDisplayMs = -1;
        currentSong = null;
        currentUrl = null;
        if (currentLine != null) {
            try { currentLine.stop(); } catch (Exception ignored) {}
            try { currentLine.flush(); } catch (Exception ignored) {}
            try { currentLine.close(); } catch (Exception ignored) {}
            currentLine = null;
        }
        if (playbackThread != null) {
            playbackThread.interrupt();
            playbackThread = null;
        }
    }

    public void togglePause() {
        if (state.get() == State.PLAYING) {
            pause();
        } else if (state.get() == State.PAUSED) {
            resume();
        }
    }

    public void setVolume(float vol) {
        this.volume = Math.max(0f, Math.min(1f, vol));
        applyVolume();
    }

    public void seekToMs(long targetMs) {
        SongInfo song = currentSong;
        if (song == null || song.duration <= 0) return;
        State s = state.get();
        if (s != State.PLAYING && s != State.PAUSED) return;
        targetMs = Math.max(0, Math.min(targetMs, song.duration));
        seekDisplayMs = targetMs;
        seekTargetMs = targetMs;
    }

    public void prevSongFallback() {
        SongInfo song = currentSong;
        if (song == null) return;
        seekToMs(0);
    }

    public void nextSongFallback() {
        // No-op without queue context
    }

    public float getVolume() { return volume; }
    public State getState() { return state.get(); }
    public SongInfo getCurrentSong() { return currentSong; }

    public float getProgress() {
        SongInfo song = currentSong;
        if (song == null || song.duration <= 0) return 0f;
        long pendingSeek = seekDisplayMs;
        if (pendingSeek >= 0) {
            return Math.max(0f, Math.min(1f, (float) pendingSeek / song.duration));
        }
        State s = state.get();
        if (s == State.STOPPED || s == State.LOADING) return 0f;
        long now = System.currentTimeMillis();
        long paused = (s == State.PAUSED) ? totalPausedMs + (now - pauseStartMs) : totalPausedMs;
        long elapsed = now - playStartMs - paused;
        return Math.max(0f, Math.min(1f, (float) elapsed / song.duration));
    }

    public long getCurrentPositionMs() {
        if (currentSong == null) return 0;
        long pendingSeek = seekDisplayMs;
        if (pendingSeek >= 0) return pendingSeek;
        long now = System.currentTimeMillis();
        State s = state.get();
        if (s == State.STOPPED || s == State.LOADING) return 0;
        long paused = (s == State.PAUSED) ? totalPausedMs + (now - pauseStartMs) : totalPausedMs;
        return Math.max(0, now - playStartMs - paused);
    }

    public float[] getSpectrumSnapshot() {
        float[] snapshot = spectrumSnapshot.get();
        return snapshot.length == 0 ? snapshot : snapshot.clone();
    }

    private void analyzePcm(byte[] buffer, int length, int channels, long generation) {
        if (generation != playbackGeneration.get() || channels <= 0) return;
        synchronized (analysisSamples) {
            int frameSize = channels * 2;
            for (int offset = 0; offset + frameSize <= length; offset += frameSize) {
                float sample = 0;
                for (int channel = 0; channel < channels; channel++) {
                    int index = offset + channel * 2;
                    short value = (short) ((buffer[index] & 0xFF) | (buffer[index + 1] << 8));
                    sample += value / 32768f;
                }
                analysisSamples[analysisSampleCount++] = sample / channels;
                if (analysisSampleCount == ANALYSIS_WINDOW) {
                    publishSpectrum(generation);
                    analysisSampleCount = 0;
                }
            }
        }
    }

    private void publishSpectrum(long generation) {
        if (generation != playbackGeneration.get()) return;
        float[] result = new float[SPECTRUM_BARS];
        for (int band = 0; band < SPECTRUM_BARS; band++) {
            double real = 0;
            double imaginary = 0;
            double frequency = (band + 1) / (double) (SPECTRUM_BARS + 1);
            for (int sample = 0; sample < ANALYSIS_WINDOW; sample++) {
                double angle = 2 * Math.PI * frequency * sample;
                real += analysisSamples[sample] * Math.cos(angle);
                imaginary -= analysisSamples[sample] * Math.sin(angle);
            }
            double magnitude = Math.sqrt(real * real + imaginary * imaginary) / ANALYSIS_WINDOW;
            result[band] = (float) Math.max(0, Math.min(1, Math.log1p(magnitude * 24) / Math.log(25)));
        }
        spectrumSnapshot.set(result);
    }

    private void playInternal(String url, long generation, SongInfo song, Runnable onNaturalEnd) {
        SourceDataLine localLine = null;
        boolean reachedEnd = false;
        boolean naturalEnd = false;
        try {
            System.out.println("[MusicPlayer] Starting playback: " + url);
            MusicHttp.StreamResponse response = MusicHttp.getInputStream(URI.create(url));
            BufferedInputStream bis = new BufferedInputStream(response.body());
            AudioInputStream rawStream = AudioSystem.getAudioInputStream(bis);
            AudioFormat baseFormat = rawStream.getFormat();

            AudioFormat decoded = new AudioFormat(
                    AudioFormat.Encoding.PCM_SIGNED,
                    baseFormat.getSampleRate(),
                    16,
                    baseFormat.getChannels(),
                    baseFormat.getChannels() * 2,
                    baseFormat.getSampleRate(),
                    false
            );

            AudioInputStream ais = AudioSystem.getAudioInputStream(decoded, rawStream);

            // compute duration from audio stream
            if (currentSong != null) {
                long frames = rawStream.getFrameLength();
                if (frames > 0) {
                    currentSong.duration = (long)(frames / baseFormat.getSampleRate() * 1000);
                } else {
                    long contentLength = response.headers().firstValueAsLong("Content-Length").orElse(-1);
                    if (contentLength > 0) {
                        int bytesPerSec = (int)(baseFormat.getSampleRate() * baseFormat.getFrameSize());
                        if (bytesPerSec > 0) {
                            currentSong.duration = (contentLength * 1000L) / bytesPerSec;
                        }
                    }
                }
                System.out.println("[MusicPlayer] Duration: " + currentSong.duration + "ms");
            }

            DataLine.Info info = new DataLine.Info(SourceDataLine.class, decoded);
            localLine = (SourceDataLine) AudioSystem.getLine(info);
            localLine.open(decoded);
            currentLine = localLine;
            applyVolume();
            if (seekTargetMs < 0 && !paused) {
                localLine.start();
            }

            long displayedPosition = seekDisplayMs;
            playStartMs = System.currentTimeMillis() - Math.max(0, displayedPosition);
            totalPausedMs = 0;
            state.set(State.PLAYING);
            bytesConsumedFromStream = 0;

            int bytesPerFrame = decoded.getFrameSize();
            int sampleRate = (int) decoded.getSampleRate();
            boolean fadeInPending = false;

            long initialSeek = seekTargetMs;
            if (initialSeek >= 0) {
                seekTargetMs = -1;
                // 先取整数帧数再乘帧大小，保证跳过量是帧对齐的，否则 16-bit 采样错位会产生噪音
                long bytesToSkip = (long) (initialSeek / 1000.0 * sampleRate) * bytesPerFrame;
                byte[] skipBuffer = new byte[8192];
                while (bytesConsumedFromStream < bytesToSkip) {
                    long remaining = bytesToSkip - bytesConsumedFromStream;
                    int skipped = ais.read(skipBuffer, 0, (int) Math.min(skipBuffer.length, remaining));
                    if (skipped < 0) break;
                    bytesConsumedFromStream += skipped;
                }
                playStartMs = System.currentTimeMillis() - initialSeek;
                totalPausedMs = 0;
                seekDisplayMs = -1;
                fadeInPending = true;
                if (paused) {
                    pauseStartMs = System.currentTimeMillis();
                } else {
                    localLine.start();
                }
            }

            byte[] buffer = new byte[4096];
            byte[] pcmBuffer = new byte[buffer.length + bytesPerFrame - 1];
            int pendingPcmLength = 0;
            int bytesRead;
            while (true) {
                bytesRead = ais.read(buffer, 0, buffer.length);
                if (bytesRead == -1) {
                    reachedEnd = true;
                    break;
                }
                bytesConsumedFromStream += bytesRead;
                if (Thread.currentThread().isInterrupted() || state.get() == State.STOPPED) break;

                // Handle seek
                long seek = seekTargetMs;
                if (seek >= 0) {
                    seekTargetMs = -1;
                    long absoluteTargetBytes = (long) (seek / 1000.0 * sampleRate) * bytesPerFrame;
                    long relativeSkip = absoluteTargetBytes - bytesConsumedFromStream;

                    localLine.stop();
                    localLine.flush();
                    pendingPcmLength = 0;
                    analysisSampleCount = 0;
                    spectrumSnapshot.set(new float[0]);

                    if (relativeSkip > 0) {
                        // Forward seek: skip bytes in current stream
                        long skipped = 0;
                        byte[] skipBuf = new byte[8192];
                        while (skipped < relativeSkip) {
                            if (Thread.currentThread().isInterrupted() || state.get() == State.STOPPED) break;
                            long toRead = Math.min(skipBuf.length, relativeSkip - skipped);
                            int n = ais.read(skipBuf, 0, (int) toRead);
                            if (n < 0) break;
                            skipped += n;
                            bytesConsumedFromStream += n;
                        }
                    } else if (relativeSkip < 0) {
                        // Backward seek: can't rewind HTTP stream, restart from URL
                        seekTargetMs = seek; // preserve for restart
                        break;
                    }

                    playStartMs = System.currentTimeMillis() - seek;
                    totalPausedMs = 0;
                    seekDisplayMs = -1;
                    fadeInPending = true;
                    if (paused) {
                        pauseStartMs = System.currentTimeMillis();
                    } else {
                        localLine.start();
                    }
                    continue;
                }

                if (paused) {
                    Thread.sleep(50);
                    continue;
                }

                System.arraycopy(buffer, 0, pcmBuffer, pendingPcmLength, bytesRead);
                int pcmLength = pendingPcmLength + bytesRead;
                int completeLength = pcmLength - pcmLength % bytesPerFrame;
                if (completeLength > 0) {
                    if (fadeInPending) {
                        applyFadeIn(pcmBuffer, completeLength, decoded.getChannels());
                        fadeInPending = false;
                    }
                    analyzePcm(pcmBuffer, completeLength, decoded.getChannels(), generation);
                    localLine.write(pcmBuffer, 0, completeLength);
                }
                pendingPcmLength = pcmLength - completeLength;
                if (pendingPcmLength > 0) {
                    System.arraycopy(pcmBuffer, completeLength, pcmBuffer, 0, pendingPcmLength);
                }
            }

            ais.close();
            rawStream.close();

            // Check for pending backward seek — restart stream from URL
            long pendingSeek = seekTargetMs;
            if (pendingSeek >= 0 && state.get() != State.STOPPED) {
                seekTargetMs = pendingSeek;
                System.out.println("[MusicPlayer] Backward seek to " + pendingSeek + "ms, restarting stream");
                playInternal(url, generation, song, onNaturalEnd); // recursive restart
                return;
            }

            naturalEnd = reachedEnd
                    && state.get() == State.PLAYING
                    && generation == playbackGeneration.get();
            if (naturalEnd) {
                state.set(State.STOPPED);
                spectrumSnapshot.set(new float[0]);
            }
            System.out.println("[MusicPlayer] Playback finished");
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        } catch (Exception e) {
            System.err.println("[MusicPlayer] Playback failed: " + e.getClass().getSimpleName() + ": " + e.getMessage());
            e.printStackTrace();
            if (state.get() != State.STOPPED && generation == playbackGeneration.get()) {
                state.set(State.STOPPED);
                spectrumSnapshot.set(new float[0]);
            }
        } finally {
            if (localLine != null) {
                try {
                    if (naturalEnd) {
                        localLine.drain();
                    } else {
                        localLine.stop();
                        localLine.flush();
                    }
                } catch (Exception ignored) {}
                try { localLine.close(); } catch (Exception ignored) {}
                if (currentLine == localLine) currentLine = null;
            }
            if (naturalEnd && generation == playbackGeneration.get() && onNaturalEnd != null) {
                onNaturalEnd.run();
            }
        }
    }

    private void applyFadeIn(byte[] pcm, int length, int channels) {
        int frameSize = channels * 2;
        int frames = Math.min(length / frameSize, 1024);
        for (int frame = 0; frame < frames; frame++) {
            float gain = frame / (float) frames;
            int frameOffset = frame * frameSize;
            for (int channel = 0; channel < channels; channel++) {
                int offset = frameOffset + channel * 2;
                short sample = (short) ((pcm[offset] & 0xFF) | (pcm[offset + 1] << 8));
                short faded = (short) (sample * gain);
                pcm[offset] = (byte) (faded & 0xFF);
                pcm[offset + 1] = (byte) ((faded >>> 8) & 0xFF);
            }
        }
    }

    private void applyVolume() {
        if (currentLine != null && currentLine.isControlSupported(FloatControl.Type.MASTER_GAIN)) {
            FloatControl gain = (FloatControl) currentLine.getControl(FloatControl.Type.MASTER_GAIN);
            float dB = (float) (Math.log(Math.max(volume, 0.0001)) / Math.log(10.0) * 20.0);
            gain.setValue(Math.max(gain.getMinimum(), Math.min(dB, gain.getMaximum())));
        }
    }
}
