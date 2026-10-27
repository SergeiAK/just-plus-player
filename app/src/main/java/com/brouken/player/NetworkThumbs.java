package com.brouken.player;

import android.content.Context;
import android.graphics.Bitmap;
import android.media.MediaDataSource;
import android.media.MediaMetadataRetriever;
import android.net.Uri;
import android.os.Build;
import android.os.SystemClock;
import android.util.Log;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;
import androidx.media3.datasource.DataSpec;
import androidx.media3.datasource.DefaultHttpDataSource;
import androidx.media3.datasource.ResolvingDataSource;

import com.bumptech.glide.Glide;
import com.bumptech.glide.Priority;
import com.bumptech.glide.load.DataSource;
import com.bumptech.glide.load.Options;
import com.bumptech.glide.load.data.DataFetcher;
import com.bumptech.glide.load.model.ModelLoader;
import com.bumptech.glide.load.model.ModelLoaderFactory;
import com.bumptech.glide.load.model.MultiModelLoaderFactory;
import com.bumptech.glide.signature.ObjectKey;

import java.io.IOException;
import java.util.Collections;

/**
 * A still of a video on an SMB share or a WebDAV server, for the browser's rows and tiles.
 *
 * <p>The platform's retriever takes a {@link MediaDataSource}, so the file is not downloaded to look
 * at it: the retriever reads the container's head, jumps to the index and then to the one keyframe it
 * needs, and every one of those reads goes out as a positioned read through the same data sources the
 * player uses - {@link SmbDataSource} with its 1 MiB window, and dav:// rewritten to http with the
 * saved place's credentials.
 *
 * <p>One at a time across the whole app. A television box has a handful of hardware decoders and a
 * list being scrolled would otherwise ask for a dozen frames at once - of 4K HEVC, over the network.
 * Glide keeps what comes back on disk, so each file costs this once.
 *
 * <p>Not for a torrent server (a frame there means the server fetching the torrent) and not for a
 * media server, which sends its own artwork.
 */
final class NetworkThumbs {

    private static final Object ONE_AT_A_TIME = new Object();
    private static boolean registered;

    private NetworkThumbs() {
    }

    static synchronized void register(final Context context) {
        if (registered) {
            return;
        }
        registered = true;
        Glide.get(context.getApplicationContext()).getRegistry()
                .prepend(Uri.class, Bitmap.class, new Factory(context.getApplicationContext()));
    }

    /** Whether a frame of this address is taken here. */
    static boolean speaks(@Nullable final Uri uri) {
        return uri != null && (SmbSessions.SCHEME.equals(uri.getScheme()) || DavFiles.speaks(uri));
    }

    private static final class Factory implements ModelLoaderFactory<Uri, Bitmap> {

        private final Context context;

        Factory(final Context context) {
            this.context = context;
        }

        @NonNull
        @Override
        public ModelLoader<Uri, Bitmap> build(@NonNull final MultiModelLoaderFactory multi) {
            return new Loader(context);
        }

        @Override
        public void teardown() {
        }
    }

    private static final class Loader implements ModelLoader<Uri, Bitmap> {

        private final Context context;

        Loader(final Context context) {
            this.context = context;
        }

        @Nullable
        @Override
        public LoadData<Bitmap> buildLoadData(@NonNull final Uri uri, final int width,
                                              final int height, @NonNull final Options options) {
            return new LoadData<>(new ObjectKey("net " + uri + " " + width + "x" + height),
                    new Fetcher(context, uri, width, height));
        }

        @Override
        public boolean handles(@NonNull final Uri uri) {
            return speaks(uri);
        }
    }

    private static final class Fetcher implements DataFetcher<Bitmap> {

        private final Context context;
        private final Uri uri;
        private final int width;
        private final int height;
        private volatile boolean cancelled;

        Fetcher(final Context context, final Uri uri, final int width, final int height) {
            this.context = context;
            this.uri = uri;
            this.width = Math.max(1, Math.min(width, 640));
            this.height = Math.max(1, Math.min(height, 360));
        }

        @Override
        public void loadData(@NonNull final Priority priority,
                             @NonNull final DataCallback<? super Bitmap> callback) {
            synchronized (ONE_AT_A_TIME) {
                if (cancelled) {
                    // Scrolled past while it waited its turn: nothing has been read, nothing is owed.
                    callback.onLoadFailed(new IOException("cancelled"));
                    return;
                }
                final MediaMetadataRetriever retriever = new MediaMetadataRetriever();
                final Source source = SmbSessions.SCHEME.equals(uri.getScheme())
                        ? new SmbSource(context, uri) : new Source(context, uri);
                final long start = SystemClock.elapsedRealtime();
                try {
                    retriever.setDataSource(source);
                    final long opened = SystemClock.elapsedRealtime();
                    final Bitmap frame = frame(retriever);
                    // What a still costs, by phase: worth having in the log while the cost is the
                    // question - a folder of fifty is fifty of these, one after another.
                    Log.d("NetworkThumbs", "open " + (opened - start) + " ms, frame "
                            + (SystemClock.elapsedRealtime() - opened) + " ms, read "
                            + source.reads + " x / " + (source.bytes >> 10) + " KiB, jumps "
                            + source.jumps + ": " + uri.getLastPathSegment());
                    if (frame == null) {
                        callback.onLoadFailed(new IOException("no frame in " + uri));
                    } else {
                        callback.onDataReady(frame);
                    }
                } catch (final Exception e) {
                    callback.onLoadFailed(e);
                } finally {
                    try {
                        retriever.release();
                    } catch (final Exception ignored) {
                        // Nothing to do about a retriever that will not close.
                    }
                    source.close();
                }
            }
        }

        /**
         * A tenth of the way in, between a second and ten minutes: far enough past the logos and the
         * fade-in that it is a picture of the film, and close enough to the head of the file that a
         * container without an index does not have to be walked for it.
         */
        private Bitmap frame(final MediaMetadataRetriever retriever) {
            long at = 1_000_000L;
            try {
                final String ms = retriever.extractMetadata(MediaMetadataRetriever.METADATA_KEY_DURATION);
                if (ms != null) {
                    at = Math.max(1_000_000L, Math.min(Long.parseLong(ms) * 100L, 600_000_000L));
                }
            } catch (final NumberFormatException ignored) {
                // No duration: a second in, as for a file on the device.
            }
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O_MR1) {
                return retriever.getScaledFrameAtTime(at, MediaMetadataRetriever.OPTION_CLOSEST_SYNC,
                        width, height);
            }
            final Bitmap full = retriever.getFrameAtTime(at, MediaMetadataRetriever.OPTION_CLOSEST_SYNC);
            if (full == null || full.getWidth() <= width) {
                return full;
            }
            final Bitmap scaled = Bitmap.createScaledBitmap(full, width,
                    Math.max(1, full.getHeight() * width / full.getWidth()), true);
            full.recycle();
            return scaled;
        }

        @Override
        public void cleanup() {
        }

        @Override
        public void cancel() {
            cancelled = true;
        }

        @NonNull
        @Override
        public Class<Bitmap> getDataClass() {
            return Bitmap.class;
        }

        @NonNull
        @Override
        public DataSource getDataSource() {
            // LOCAL so Glide keeps the decoded still on disk rather than trying to keep the "source",
            // which here is a whole film.
            return DataSource.LOCAL;
        }
    }

    /**
     * An SMB file held open for the whole retrieval and read where the retriever asks.
     *
     * <p>Not through {@link SmbDataSource}: that opens the file afresh on every {@code open()} - a
     * create, a stat, and its 1 MiB window thrown away - which is right for a player that opens once
     * and streams, and ruinous for a retriever that jumps. Measured on the Dune over Wi-Fi: a 1080p MKV
     * took 20.7 s to open for 1.4 MiB read, in 70 jumps - about 300 ms a jump and nearly nothing a byte.
     * Here a jump is only a positioned read, and a 1 MiB window under it turns the retriever's
     * few-kilobyte reads into one request each.
     */
    private static final class SmbSource extends Source {

        // 1 MiB, SMB 3's largest read: on the Dune's Wi-Fi a request cost about as much as its bytes at
        // 256 KiB (an MP4's index came to 12 MiB in 45 requests and 3 s), so fewer, larger ones.
        private static final int WINDOW = 1024 * 1024;

        private final Context context;
        private final Uri uri;
        @Nullable
        private com.hierynomus.smbj.share.File file;
        private final byte[] window = new byte[WINDOW];
        private long windowStart = -1;
        private int windowLength;
        private long length = -1;

        SmbSource(final Context context, final Uri uri) {
            super(context, uri);
            this.context = context;
            this.uri = uri;
        }

        private com.hierynomus.smbj.share.File file() throws IOException {
            if (file == null) {
                try {
                    file = SmbSessions.open(context, uri);
                    length = file.getFileInformation().getStandardInformation().getEndOfFile();
                } catch (final IOException e) {
                    throw e;
                } catch (final Exception e) {
                    throw new IOException(e);
                }
            }
            return file;
        }

        @Override
        public synchronized int readAt(final long at, final byte[] buffer, final int offset,
                                       final int size) throws IOException {
            if (size == 0) {
                return 0;
            }
            final com.hierynomus.smbj.share.File open = file();
            if (at >= length) {
                return -1;
            }
            reads++;
            if (at < windowStart || at >= windowStart + windowLength) {
                jumps++;
                final int fill;
                try {
                    fill = open.read(window, at, 0, (int) Math.min(WINDOW, length - at));
                } catch (final Exception e) {
                    throw new IOException(e);
                }
                if (fill <= 0) {
                    return -1;
                }
                windowStart = at;
                windowLength = fill;
                bytes += fill;
            }
            final int from = (int) (at - windowStart);
            final int served = Math.min(size, windowLength - from);
            System.arraycopy(window, from, buffer, offset, served);
            return served;
        }

        @Override
        public synchronized long getSize() throws IOException {
            file();
            return length;
        }

        @Override
        public synchronized void close() {
            if (file != null) {
                try {
                    file.close();
                } catch (final Exception ignored) {
                    // A closed handle on a connection that has already gone is not news.
                }
                file = null;
            }
        }
    }

    /**
     * The retriever's reads, served by a media3 data source. Reads that follow on from the last one
     * carry on with the open source; a jump closes it and opens again where the retriever wants -
     * which over SMB is the cost of one request, since the read there is positioned anyway.
     */
    private static class Source extends MediaDataSource {

        private final Uri uri;
        private final androidx.media3.datasource.DataSource source;
        private long size = -2;
        int reads;
        int jumps;
        long bytes;
        private long position = -1;
        private boolean open;

        Source(final Context context, final Uri uri) {
            this.uri = uri;
            final androidx.media3.datasource.DataSource.Factory http =
                    new ResolvingDataSource.Factory(new DefaultHttpDataSource.Factory(), spec -> {
                        if (!DavFiles.speaks(spec.uri)) {
                            return spec;
                        }
                        final DataSpec asHttp = spec.buildUpon().setUri(DavFiles.http(spec.uri)).build();
                        final String authorization = NetworkFiles.authorization(context, spec.uri);
                        return authorization == null ? asHttp : asHttp.withAdditionalHeaders(
                                Collections.singletonMap("Authorization", authorization));
                    });
            source = new SmbDataSource.Factory(context, http).createDataSource();
        }

        @Override
        public synchronized int readAt(final long at, final byte[] buffer, final int offset,
                                       final int length) throws IOException {
            if (length == 0) {
                return 0;
            }
            final long total = getSize();
            if (total >= 0 && at >= total) {
                return -1;
            }
            reads++;
            if (!open || at != position) {
                jumps++;
                close();
                source.open(new DataSpec.Builder().setUri(uri).setPosition(at).build());
                open = true;
                position = at;
            }
            int done = 0;
            while (done < length) {
                final int read = source.read(buffer, offset + done, length - done);
                if (read == androidx.media3.common.C.RESULT_END_OF_INPUT) {
                    break;
                }
                done += read;
            }
            position += done;
            bytes += done;
            return done == 0 ? -1 : done;
        }

        @Override
        public synchronized long getSize() throws IOException {
            if (size == -2) {
                // Asked of the source once, by opening it at the head: the length comes back from
                // the open, and the retriever's first read is at the head anyway.
                close();
                final long length = source.open(new DataSpec.Builder().setUri(uri).build());
                open = true;
                position = 0;
                size = length == androidx.media3.common.C.LENGTH_UNSET ? -1 : length;
            }
            return size;
        }

        @Override
        public synchronized void close() {
            if (open) {
                open = false;
                try {
                    source.close();
                } catch (final IOException ignored) {
                    // A source that will not close has nothing more to give either way.
                }
            }
        }
    }
}
