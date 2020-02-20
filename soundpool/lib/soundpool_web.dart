/// @nodoc
library soundpool_web;

import 'dart:async';
import 'dart:web_audio' as audio;
import 'dart:typed_data';

import 'package:flutter/services.dart';
import 'package:flutter_web_plugins/flutter_web_plugins.dart';
import 'package:http/http.dart' as http;

/// @nodoc
class SoundpoolPlugin {
  static void registerWith(Registrar registrar) {
    final MethodChannel channel = MethodChannel(
        'pl.ukaszapps/soundpool',
        const StandardMethodCodec(),
        registrar.messenger);
    final SoundpoolPlugin instance = SoundpoolPlugin();
    channel.setMethodCallHandler(instance.handleMethodCall);
  }

  final Map<int, _AudioContextWrapper> _pool = {};

  Future<dynamic> handleMethodCall(MethodCall call) async {
    final supported = audio.AudioContext.supported;
    if (!supported){
      throw UnsupportedError('Required AudioContext API is not supported by this browser.');
    }

    switch (call.method) {
      case 'initSoundpool':
        //{"maxStreams": _maxStreams, "streamType": _streamType.index}
        // stream type and streams limit are not supported
        var wrapperIndex = _pool.length+1;
        _pool[wrapperIndex] = _AudioContextWrapper();
        return wrapperIndex;
      case 'load':
        //{"poolId": poolId, "rawSound": rawSound, "priority": priority});
        Uint8List rawSound = call.arguments['rawSound'];
        Uint8List rawSoundCopy = Uint8List.fromList(rawSound);
        int id = call.arguments['poolId'];
        _AudioContextWrapper wrapper = _pool[id];
        return await wrapper.load(rawSoundCopy.buffer);

      case 'loadUri':
      // {"poolId": poolId, "uri": uri, "priority": priority}
        var uri = call.arguments['uri'] as String;
        int id = call.arguments['poolId'];
        _AudioContextWrapper wrapper = _pool[id];
        return await wrapper.loadUri(uri);

      case 'play':
      // {"poolId": poolId, "soundId": soundId, "repeat": repeat}
        int id = call.arguments['poolId'];
        _AudioContextWrapper wrapper = _pool[id];
        var soundId = call.arguments['soundId'] as int;
        var rate = call.arguments['rate'] as double;
        return await wrapper.play(soundId, rate: rate);

      case 'stop':
        // {"poolId": poolId, "streamId": streamId,}
        int id = call.arguments['poolId'];
        _AudioContextWrapper wrapper = _pool[id];
        var streamId = call.arguments['streamId'] as int;
        return await wrapper.stop(streamId);

      case 'setVolume': 
      // {
      //         "poolId": poolId,
      //         "soundId": soundId,
      //         "streamId": streamId,
      //         "volumeLeft": volumeLeft,
      //         "volumeRight": volumeRight,
      //       }

        int id = call.arguments['poolId'];
        _AudioContextWrapper wrapper = _pool[id];
        var streamId = call.arguments['streamId'];
        var soundId = call.arguments['soundId'];
        var volumeLeft = call.arguments['volumeLeft'];
        var volumeRight = call.arguments['volumeRight'];
        if (streamId == null) {
          wrapper.setVolume(soundId, volumeLeft, volumeRight);
        } else {
          wrapper.setStreamVolume(streamId, volumeLeft, volumeRight);
        }
        return;
      case 'setRate':
        // {
        //       "poolId": poolId,
        //       "streamId": streamId,
        //       "rate": playbackRate,
        // }
        int id = call.arguments['poolId'];
        _AudioContextWrapper wrapper = _pool[id];
        var streamId = call.arguments['streamId'] as int;
        double rate = call.arguments['rate'] as double ?? 1.0;
        wrapper.setStreamRate(streamId, rate);
        return;
      case 'release':
        int id = call.arguments['poolId'];
        _AudioContextWrapper wrapper = _pool[id];
        wrapper.release();
        return;

      case 'dispose':
        int id = call.arguments['poolId'];
        _AudioContextWrapper wrapper = _pool.remove(id);
        return await wrapper.dispose();

      default:
        throw PlatformException(
            code: 'Unimplemented',
            details: "The soundpool plugin for web doesn't implement "
                "the method '${call.method}'");
    }
  }

}

class _AudioContextWrapper {

  audio.AudioContext audioContext;
  void _initContext(){
    if (audioContext == null){
      audioContext = audio.AudioContext();
    }
  }

  Map<int, _CachedAudioSettings> _cache = {};
  Map<int, _PlayingAudioWrapper> _playedAudioCache = {};
  int _lastPlayedStreamId = 0;

  Future<int> load(ByteBuffer buffer) async {
    _initContext();
    audio.AudioBuffer audioBuffer = await audioContext.decodeAudioData(buffer);
    int currentSize = _cache.length;
    _cache[currentSize+1] = _CachedAudioSettings(buffer:audioBuffer);
    return currentSize+1;
  }

  Future<int> loadUri(String uri) async {
    var response = await http.get(uri);
    Uint8List buffer = response.bodyBytes;
    return await load(buffer.buffer);
  }

  Future<int> play(int soundId, {double rate = 1.0}) async {
    _CachedAudioSettings cachedAudio= _cache[soundId];
    audio.AudioBuffer audioBuffer = cachedAudio.buffer;
    var playbackRate = rate ?? 1.0;

    var sampleSource = audioContext.createBufferSource();
    sampleSource.buffer = audioBuffer;
    // updating playback rate
    sampleSource.playbackRate.value = playbackRate;
    // gain node for setting volume level
    var gainNode = audioContext.createGain();
    gainNode.gain.value = cachedAudio.volumeLeft;

    sampleSource.connectNode(gainNode);
    gainNode.connectNode(audioContext.destination);
    _lastPlayedStreamId = _lastPlayedStreamId+1;
    var streamId = _lastPlayedStreamId;
    var subscription = sampleSource.onEnded.listen((_){
      var audioWrapper = _playedAudioCache.remove(streamId);
      audioWrapper?.subscription?.cancel();
    });
    _playedAudioCache[streamId] = _PlayingAudioWrapper(sourceNode: sampleSource, gainNode: gainNode, subscription: subscription,soundId: soundId,);
    
    sampleSource.start();
    return streamId;
  }

  Future<void> stop(int streamId) async {
    _PlayingAudioWrapper audioWrapper =  _playedAudioCache.remove(streamId);
    audioWrapper?.subscription?.cancel();
    audioWrapper?.sourceNode?.stop();
  }

  Future<void> setVolume(int soundId, double volumeLeft, double volumeRight) async {
    _CachedAudioSettings cachedAudio= _cache[soundId];
    cachedAudio.volumeLeft = volumeLeft;
    cachedAudio.volumeRight = volumeRight;
    _playedAudioCache.values
      .where((pw)=>pw.soundId == soundId)
      .forEach((playingWrapper) {
          playingWrapper.gainNode.gain.value = volumeLeft;
        });
  }

  Future<void> setStreamVolume(int streamId, double volumeLeft, double volumeRight) async {
    _PlayingAudioWrapper playingWrapper = _playedAudioCache[streamId];
    if (playingWrapper != null){
      playingWrapper.gainNode.gain.value = volumeLeft;
      _CachedAudioSettings cachedAudio= _cache[playingWrapper.soundId];
      cachedAudio?.volumeLeft = volumeLeft;
      cachedAudio?.volumeRight = volumeRight;
    }
  }

  Future<void> setStreamRate(int streamId, double rate) async {
    _PlayingAudioWrapper playingWrapper = _playedAudioCache[streamId];
    if (playingWrapper != null){
      playingWrapper.sourceNode.playbackRate.value = rate;
    }
  }

  Future<void> release() async {
    _cache.clear();
  }

  Future<void> dispose() {
    release();
    audioContext?.close();
  }
}

class _CachedAudioSettings {
  final audio.AudioBuffer buffer;
  double volumeLeft;
  double volumeRight;
  _CachedAudioSettings({this.buffer, this.volumeLeft = 1.0, this.volumeRight = 1.0}):assert(buffer != null);
}

class _PlayingAudioWrapper {
  final audio.AudioBufferSourceNode sourceNode;
  final audio.GainNode gainNode;
  final StreamSubscription subscription;
  final int soundId;
  const _PlayingAudioWrapper({this.sourceNode, this.gainNode, this.subscription, this.soundId}):assert(soundId != null), assert(sourceNode != null),assert(gainNode != null);
}