// Copyright 2017 The Chromium Authors. All rights reserved.
// Use of this source code is governed by a BSD-style license that can be
// found in the LICENSE file.

package io.flutter.plugins.videoplayer;

import static com.google.android.exoplayer2.Player.REPEAT_MODE_ALL;
import static com.google.android.exoplayer2.Player.REPEAT_MODE_OFF;

import android.content.Context;
import android.media.AudioManager;
import android.net.Uri;
import android.os.Build;
import android.util.Log;
import android.view.Surface;
import com.google.android.exoplayer2.C;
import com.google.android.exoplayer2.DefaultLoadControl;
import com.google.android.exoplayer2.ExoPlaybackException;
import com.google.android.exoplayer2.ExoPlayerFactory;
import com.google.android.exoplayer2.Player;
import com.google.android.exoplayer2.Player.DefaultEventListener;
import com.google.android.exoplayer2.SimpleExoPlayer;
import com.google.android.exoplayer2.Timeline;
import com.google.android.exoplayer2.audio.AudioAttributes;
import com.google.android.exoplayer2.source.MediaSource;
import com.google.android.exoplayer2.source.hls.HlsMediaSource;
import com.google.android.exoplayer2.trackselection.DefaultTrackSelector;
import com.google.android.exoplayer2.trackselection.TrackSelector;
import com.google.android.exoplayer2.upstream.DataSource;
import com.google.android.exoplayer2.upstream.DefaultBandwidthMeter;
import com.google.android.exoplayer2.upstream.DefaultDataSourceFactory;
import com.google.android.exoplayer2.upstream.DefaultHttpDataSource;
import com.google.android.exoplayer2.upstream.DefaultHttpDataSourceFactory;
import io.flutter.plugin.common.EventChannel;
import io.flutter.plugin.common.MethodCall;
import io.flutter.plugin.common.MethodChannel;
import io.flutter.plugin.common.MethodChannel.MethodCallHandler;
import io.flutter.plugin.common.MethodChannel.Result;
import io.flutter.plugin.common.PluginRegistry.Registrar;
import io.flutter.view.TextureRegistry;

import java.time.LocalDate;
import java.util.Arrays;
import java.util.Collections;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

public class VideoPlayerPlugin implements MethodCallHandler {

  private static class VideoPlayer {

    private SimpleExoPlayer exoPlayer;

    private Surface surface;

    private final TextureRegistry.SurfaceTextureEntry textureEntry;

    private EventChannel.EventSink eventSink;

    private final EventChannel eventChannel;

    private boolean isInitialized = false;

    VideoPlayer(
        Context context,
        EventChannel eventChannel,
        TextureRegistry.SurfaceTextureEntry textureEntry,
        String dataSource,
        Result result) {
      this.eventChannel = eventChannel;
      this.textureEntry = textureEntry;

      DefaultBandwidthMeter.Builder bandwidth = new DefaultBandwidthMeter.Builder();
//      bandwidth.setInitialBitrateEstimate(300000);

      TrackSelector trackSelector = new DefaultTrackSelector(bandwidth.build());
      DefaultLoadControl.Builder loadControlBuilder = new DefaultLoadControl.Builder();
//      loadControlBuilder
//              .setBufferDurationsMs(10000, 20000, 2500, 2500);

      exoPlayer = ExoPlayerFactory.newSimpleInstance(context, trackSelector, loadControlBuilder.createDefaultLoadControl());

      Uri uri = Uri.parse(dataSource);

      DataSource.Factory dataSourceFactory;
      if (uri.getScheme().equals("asset") || uri.getScheme().equals("file")) {
        dataSourceFactory = new DefaultDataSourceFactory(context, "ExoPlayer");
      } else {
        dataSourceFactory =
            new DefaultHttpDataSourceFactory(
                "ExoPlayer",
                null,
                DefaultHttpDataSource.DEFAULT_CONNECT_TIMEOUT_MILLIS,
                DefaultHttpDataSource.DEFAULT_READ_TIMEOUT_MILLIS,
                true);
      }

      MediaSource mediaSource =
          new HlsMediaSource.Factory(dataSourceFactory).createMediaSource(uri);
      exoPlayer.prepare(mediaSource);

      setupVideoPlayer(eventChannel, textureEntry, result);
    }

    private void setupVideoPlayer(
        EventChannel eventChannel,
        TextureRegistry.SurfaceTextureEntry textureEntry,
        Result result) {

      eventChannel.setStreamHandler(
          new EventChannel.StreamHandler() {
            @Override
            public void onListen(Object o, EventChannel.EventSink sink) {
              eventSink = sink;
            }

            @Override
            public void onCancel(Object o) {
              eventSink = null;
            }
          });

      surface = new Surface(textureEntry.surfaceTexture());
      exoPlayer.setVideoSurface(surface);
      setAudioAttributes(exoPlayer);

      exoPlayer.addListener(
          new DefaultEventListener() {

            @Override
            public void onPlayerStateChanged(final boolean playWhenReady, final int playbackState) {
              super.onPlayerStateChanged(playWhenReady, playbackState);
              if (playbackState == Player.STATE_BUFFERING) {
                if (eventSink != null) {
                  Map<String, Object> event = new HashMap<>();
                  event.put("event", "bufferingUpdate");
                  List<Integer> range = Arrays.asList(0, exoPlayer.getBufferedPercentage());
                  // iOS supports a list of buffered ranges, so here is a list with a single range.
                  event.put("values", Collections.singletonList(range));
                  eventSink.success(event);
                }
              } else if (playbackState == Player.STATE_READY && !isInitialized) {
                isInitialized = true;
                sendInitialized();
              }
            }

            @Override
            public void onPlayerError(final ExoPlaybackException error) {
              super.onPlayerError(error);
              if (eventSink != null) {
                eventSink.error("VideoError", "Video player had error " + error, null);
              }
            }
          });

      Map<String, Object> reply = new HashMap<>();
      reply.put("textureId", textureEntry.id());
      result.success(reply);
    }

    @SuppressWarnings("deprecation")
    private static void setAudioAttributes(SimpleExoPlayer exoPlayer) {
      if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.LOLLIPOP) {
        exoPlayer.setAudioAttributes(
            new AudioAttributes.Builder().setContentType(C.CONTENT_TYPE_MOVIE).build());
      } else {
        exoPlayer.setAudioStreamType(AudioManager.STREAM_MUSIC);
      }
    }

    void play() { exoPlayer.setPlayWhenReady(true); }

    void pause() { exoPlayer.setPlayWhenReady(false); }

    void setLooping(boolean value) {
      exoPlayer.setRepeatMode(value ? REPEAT_MODE_ALL : REPEAT_MODE_OFF);
    }

    void setVolume(double value) {
      float bracketedValue = (float) Math.max(0.0, Math.min(1.0, value));
      exoPlayer.setVolume(bracketedValue);
    }

    void seekTo(int location) {
      exoPlayer.seekTo(location);
    }

    long getPosition() {
      return exoPlayer.getCurrentPosition();
    }

    int seekToDateIterations = 0;
    long lastDelay;

    void seekToDate(long whereToPutTheHeadInUTC, long acceptableDelay) {
      Timeline timeline = exoPlayer.getCurrentTimeline();

      // the absolute start time in UTC of the current window
      long windowStartTimeMs = timeline.getWindow(0, new Timeline.Window()).windowStartTimeMs;

      Log.d("windowStartTimeMs", Long.valueOf(windowStartTimeMs).toString());

      long newPosition = whereToPutTheHeadInUTC - windowStartTimeMs;
      Log.d("newPosition", Long.valueOf(newPosition).toString());

      long delay = exoPlayer.getCurrentPosition() - newPosition;
      Log.d("delay", Long.valueOf(delay).toString());

      if (Math.abs(delay) > acceptableDelay) {
        exoPlayer.seekTo(newPosition);
      }
    }

    private void sendInitialized() {
      if (isInitialized && eventSink != null) {
        Map<String, Object> event = new HashMap<>();
        event.put("event", "initialized");
        event.put("duration", exoPlayer.getDuration());
        if (exoPlayer.getVideoFormat() != null) {
          event.put("width", exoPlayer.getVideoFormat().width);
          event.put("height", exoPlayer.getVideoFormat().height);
        }
        eventSink.success(event);
      }
    }

    void dispose() {
      if (isInitialized) {
        exoPlayer.stop();
      }
      textureEntry.release();
      eventChannel.setStreamHandler(null);
      if (surface != null) {
        surface.release();
      }
      if (exoPlayer != null) {
        exoPlayer.release();
      }
    }
  }

  public static void registerWith(Registrar registrar) {
    final MethodChannel channel =
        new MethodChannel(registrar.messenger(), "flutter.io/videoPlayer");
    channel.setMethodCallHandler(new VideoPlayerPlugin(registrar));
  }

  private VideoPlayerPlugin(Registrar registrar) {
    this.registrar = registrar;
    this.videoPlayers = new HashMap<>();
  }

  private final Map<Long, VideoPlayer> videoPlayers;

  private final Registrar registrar;

  @Override
  public void onMethodCall(MethodCall call, Result result) {
    TextureRegistry textures = registrar.textures();
    if (textures == null) {
      result.error("no_activity", "video_player plugin requires a foreground activity", null);
      return;
    }
    switch (call.method) {
      case "init":
        for (VideoPlayer player : videoPlayers.values()) {
          player.dispose();
        }
        videoPlayers.clear();
        break;
      case "create":
        {
          TextureRegistry.SurfaceTextureEntry handle = textures.createSurfaceTexture();
          EventChannel eventChannel =
              new EventChannel(
                  registrar.messenger(), "flutter.io/videoPlayer/videoEvents" + handle.id());

          VideoPlayer player;
          if (call.argument("asset") != null) {
            String assetLookupKey;
            if (call.argument("package") != null) {
              assetLookupKey =
                  registrar.lookupKeyForAsset(
                      (String) call.argument("asset"), (String) call.argument("package"));
            } else {
              assetLookupKey = registrar.lookupKeyForAsset((String) call.argument("asset"));
            }
            player =
                new VideoPlayer(
                    registrar.context(),
                    eventChannel,
                    handle,
                    "asset:///" + assetLookupKey,
                    result);
            videoPlayers.put(handle.id(), player);
          } else {
            player =
                new VideoPlayer(
                    registrar.context(),
                    eventChannel,
                    handle,
                    (String) call.argument("uri"),
                    result);
            videoPlayers.put(handle.id(), player);
          }
          break;
        }
      default:
        {
          long textureId = ((Number) call.argument("textureId")).longValue();
          VideoPlayer player = videoPlayers.get(textureId);
          if (player == null) {
            result.error(
                "Unknown textureId",
                "No video player associated with texture id " + textureId,
                null);
            return;
          }
          onMethodCall(call, result, textureId, player);
          break;
        }
    }
  }

  private void onMethodCall(MethodCall call, Result result, long textureId, VideoPlayer player) {
    switch (call.method) {
      case "setLooping":
        player.setLooping((Boolean) call.argument("looping"));
        result.success(null);
        break;
      case "setVolume":
        player.setVolume((Double) call.argument("volume"));
        result.success(null);
        break;
      case "play":
        player.play();
        result.success(null);
        break;
      case "pause":
        player.pause();
        result.success(null);
        break;
      case "seekTo":
        int location = ((Number) call.argument("location")).intValue();
        player.seekTo(location);
        result.success(null);
        break;
      case "seekToDate":
        long whereToPutTheHeadInUTC = ((Number) call.argument("whereToPutTheHeadInUTC")).longValue();
        long acceptableDelay = ((Number) call.argument("acceptableDelay")).longValue();
        player.seekToDate(whereToPutTheHeadInUTC, acceptableDelay);
        result.success(player.getPosition());
        break;
      case "position":
        result.success(player.getPosition());
        break;
      case "dispose":
        player.dispose();
        videoPlayers.remove(textureId);
        result.success(null);
        break;
      default:
        result.notImplemented();
        break;
    }
  }
}
