package com.guichaguri.trackplayer.service.player;

import android.content.Context;
import android.util.Log;

import com.facebook.react.bridge.Promise;
import com.google.android.exoplayer2.C;
import com.google.android.exoplayer2.ExoPlayer;
import com.google.android.exoplayer2.PlaybackException;
import com.google.android.exoplayer2.Player;
import com.google.android.exoplayer2.database.DatabaseProvider;
import com.google.android.exoplayer2.database.StandaloneDatabaseProvider;
import com.google.android.exoplayer2.source.ConcatenatingMediaSource;
import com.google.android.exoplayer2.source.MediaSource;
import com.google.android.exoplayer2.upstream.DataSource;
import com.google.android.exoplayer2.upstream.cache.CacheDataSource;
import com.google.android.exoplayer2.upstream.cache.LeastRecentlyUsedCacheEvictor;
import com.google.android.exoplayer2.upstream.cache.SimpleCache;
import com.guichaguri.trackplayer.service.MusicManager;
import com.guichaguri.trackplayer.service.Utils;
import com.guichaguri.trackplayer.service.models.Track;

import java.io.File;
import java.util.ArrayList;
import java.util.Collection;
import java.util.Collections;
import java.util.List;

/**
 * @author Guichaguri
 */
public class LocalPlayback extends ExoPlayback<ExoPlayer> {

    private final long cacheMaxSize;

    private SimpleCache cache;
    private ConcatenatingMediaSource source;
    private boolean prepared = false;
    private boolean enabledAudioOffload = false;

    public LocalPlayback(Context context, MusicManager manager, ExoPlayer player, long maxCacheSize,
                         boolean autoUpdateMetadata) {
        super(context, manager, player, autoUpdateMetadata);
        this.cacheMaxSize = maxCacheSize;
    }

    @Override
    public void initialize() {
        if(cacheMaxSize > 0) {
            File cacheDir = new File(context.getCacheDir(), "TrackPlayer");
            DatabaseProvider db = new StandaloneDatabaseProvider(context);
            cache = new SimpleCache(cacheDir, new LeastRecentlyUsedCacheEvictor(cacheMaxSize), db);
        } else {
            cache = null;
        }

        super.initialize();

        resetQueue();
    }

    public DataSource.Factory enableCaching(DataSource.Factory ds) {
        if(cache == null || cacheMaxSize <= 0) return ds;

        return new CacheDataSource.Factory()
                                .setCache(cache)
                                .setUpstreamDataSourceFactory(ds)
                                .setFlags(CacheDataSource.FLAG_IGNORE_CACHE_ON_ERROR);
    }

    private void prepare() {
        if(!prepared) {
            Log.d(Utils.LOG, "Preparing the media source...");
            player.setMediaSource(source, false);
            player.prepare();
            prepared = true;
        }
    }

    @Override
    public void add(Track track, int index, Promise promise) {
        queue.add(index, track);
        MediaSource trackSource = track.toMediaSource(context, this);
        source.addMediaSource(index, trackSource, manager.getHandler(), () -> promise.resolve(index));

        prepare();
    }

    @Override
    public void add(Collection<Track> tracks, int index, Promise promise) {
        List<MediaSource> trackList = new ArrayList<>();

        for(Track track : tracks) {
            trackList.add(track.toMediaSource(context, this));
        }

        queue.addAll(index, tracks);
        source.addMediaSources(index, trackList, manager.getHandler(), () -> promise.resolve(index));

        prepare();
    }

    @Override
    public void remove(List<Integer> indexes, Promise promise) {
        int currentIndex = player.getCurrentMediaItemIndex();

        // Sort the list so we can loop through sequentially
        Collections.sort(indexes);

        for(int i = indexes.size() - 1; i >= 0; i--) {
            int index = indexes.get(i);

            // Skip indexes that are the current track or are out of bounds
            if(index == currentIndex || index < 0 || index >= queue.size()) {
                // Resolve the promise when the last index is invalid
                if(i == 0) promise.resolve(null);
                continue;
            }

            queue.remove(index);

            if(i == 0) {
                source.removeMediaSource(index, manager.getHandler(), Utils.toRunnable(promise));
            } else {
                source.removeMediaSource(index);
            }

            // Fix the window index
            if (index < lastKnownWindow) {
                lastKnownWindow--;
            }
        }
    }

    @Override
    public void removeUpcomingTracks() {
        int currentIndex = player.getCurrentMediaItemIndex();
        if (currentIndex == C.INDEX_UNSET) return;

        for (int i = queue.size() - 1; i > currentIndex; i--) {
            queue.remove(i);
            source.removeMediaSource(i);
        }
    }

    @Override
    public void setRepeatMode(int repeatMode) {
        player.setRepeatMode(repeatMode);
    }

    public int getRepeatMode() {
        return player.getRepeatMode();
    }

    private void resetQueue() {
        queue.clear();

        source = new ConcatenatingMediaSource();
        player.setMediaSource(source, true);
        player.prepare();
        prepared = false; // We set it to false as the queue is now empty

        lastKnownWindow = C.INDEX_UNSET;
        lastKnownPosition = C.POSITION_UNSET;

        manager.onReset();
    }

    @Override
    public void play() {
        prepare();
        super.play();
    }

    @Override
    public void stop() {
        super.stop();
        prepared = false;
    }

    @Override
    public void seekTo(long time) {
        prepare();
        super.seekTo(time);
    }

    @Override
    public void reset() {
        Integer track = getCurrentTrackIndex();
        long position = player.getCurrentPosition();

        super.reset();
        resetQueue();

        manager.onTrackUpdate(track, position, null, null);
    }

    @Override
    public float getPlayerVolume() {
        return player.getVolume();
    }

    @Override
    public void setPlayerVolume(float volume) {
        player.setVolume(volume);
    }

    @Override
    public void onPlaybackStateChanged(int playbackState) {
        if(playbackState == Player.STATE_ENDED) {
            prepared = false;
        }

        super.onPlaybackStateChanged(playbackState);
    }

    @Override
    public void onPlayerError(PlaybackException error) {
        prepared = false;
        super.onPlayerError(error);
    }

    @Override
    public void destroy() {
        super.destroy();

        if(cache != null) {
            try {
                cache.release();
                cache = null;
            } catch(Exception ex) {
                Log.w(Utils.LOG, "Couldn't release the cache properly", ex);
            }
        }
    }

    // @Override
    // public void onExperimentalOffloadSchedulingEnabledChanged(boolean offloadSchedulingEnabled) {
    //     Log.d(Utils.LOG, "onExperimentalOffloadSchedulingEnabledChanged: " + offloadSchedulingEnabled);

    // }

    @Override
    public void enableAudioOffload(boolean enabled) {
        if (
            enabledAudioOffload == enabled ||
            (enabled && !manager.shouldEnableAudioOffload())
        ) return;
        player.experimentalSetOffloadSchedulingEnabled(enabled);
        Log.d(Utils.LOG, "enableAudioOffload: " + (enabled ? "true" : "false"));
        enabledAudioOffload = enabled;
    }
}
