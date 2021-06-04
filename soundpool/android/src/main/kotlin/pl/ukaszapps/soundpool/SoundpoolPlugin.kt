package pl.ukaszapps.soundpool

import android.content.Context
import android.media.AudioAttributes
import android.media.AudioManager
import android.media.SoundPool
import android.net.Uri
import android.os.Build
import android.os.Handler
import android.os.Looper
import io.flutter.plugin.common.MethodCall
import io.flutter.plugin.common.MethodChannel
import io.flutter.plugin.common.MethodChannel.MethodCallHandler
import io.flutter.plugin.common.MethodChannel.Result
import io.flutter.plugin.common.PluginRegistry.Registrar
import java.io.File
import java.io.FileOutputStream
import java.net.URI
import java.util.concurrent.Executor
import java.util.concurrent.Executors


internal val loadExecutor: Executor = Executors.newCachedThreadPool()

internal val uiThreadHandler: Handler = Handler(Looper.getMainLooper())
class SoundpoolPlugin(context: Context) : MethodCallHandler {
    companion object {
        @JvmStatic
        fun registerWith(registrar: Registrar) {
            val channel = MethodChannel(registrar.messenger(), CHANNEL_NAME)

            channel.setMethodCallHandler(SoundpoolPlugin(registrar.context()))

            // clearing temporary files from previous session
            with(registrar.context().cacheDir) { list { _, name -> name.matches("sound(.*)pool".toRegex()) }.forEach { File(this, it).delete() } }
        }

        private const val CHANNEL_NAME = "pl.ukaszapps/soundpool"
    }

    private val application = context.applicationContext

    private val wrappers: MutableList<SoundpoolWrapper> = mutableListOf()

    override fun onMethodCall(call: MethodCall, result: Result) {
        when (call.method) {
            "initSoundpool" -> {
                val arguments = call.arguments as Map<String, Int>
                val streamTypeIndex = arguments["streamType"]
                val maxStreams = arguments["maxStreams"] ?: 1
                val streamType = when (streamTypeIndex) {
                    0 -> AudioManager.STREAM_RING
                    1 -> AudioManager.STREAM_ALARM
                    2 -> AudioManager.STREAM_MUSIC
                    3 -> AudioManager.STREAM_NOTIFICATION
                    else -> -1
                }
                if (streamType > -1) {
                    val wrapper = SoundpoolWrapper(application, maxStreams, streamType)
                    val index = wrappers.size
                    wrappers.add(wrapper)
                    result.success(index)
                } else {
                    result.success(-1)
                }
            }
            "dispose" -> {
                val arguments = call.arguments as Map<String, Int>
                val poolIndex = arguments["poolId"]!!
                wrappers[poolIndex].releaseSoundpool()
                wrappers.removeAt(poolIndex)
                result.success(null)
            }
            else -> {
                val arguments = call.arguments as Map<String, Any>
                val poolIndex = arguments["poolId"] as Int
                wrappers[poolIndex].onMethodCall(call, result)
            }
        }
    }
}

internal data class VolumeInfo(val left: Float = 1.0f, val right: Float = 1.0f);

/**
 * Wraps Soundpool instance and handles instance-level method calls
 */
internal class SoundpoolWrapper(private val context: Context, private val maxStreams: Int, private val streamType: Int) {
    companion object {

        private val DEFAULT_VOLUME_INFO = VolumeInfo()
    }

    private var soundPool = createSoundpool()

    private val loadingSoundsMap = HashMap<Int, Result>()

    private val moderateList = listOf<Int>(
        R.raw.moderate_1,
        R.raw.moderate_2,
        R.raw.moderate_3,
        R.raw.moderate_4,
        R.raw.moderate_5,
        R.raw.moderate_6,
        R.raw.moderate_7,
        R.raw.moderate_8,
        R.raw.moderate_9,
        R.raw.moderate_10,
        R.raw.moderate_11,
        R.raw.moderate_12,
        R.raw.moderate_13,
        R.raw.moderate_14,
        R.raw.moderate_15,
        R.raw.moderate_16,
        R.raw.moderate_17,
        R.raw.moderate_18,
        R.raw.moderate_19,
        R.raw.moderate_20,
        R.raw.moderate_21,
        R.raw.moderate_22,
        R.raw.moderate_23,
        R.raw.moderate_24,
        R.raw.moderate_25,
        R.raw.moderate_26,
        R.raw.moderate_27,
        R.raw.moderate_28,
        R.raw.moderate_29,
        R.raw.moderate_30,
        R.raw.moderate_31,
        R.raw.moderate_32,
        R.raw.moderate_33,
        R.raw.moderate_34,
        R.raw.moderate_35,
        R.raw.moderate_36,
        R.raw.moderate_37,
        R.raw.moderate_38,
        R.raw.moderate_39,
        R.raw.moderate_40,
        R.raw.moderate_41,
        R.raw.moderate_42,
        R.raw.moderate_43,
        R.raw.moderate_44,
        R.raw.moderate_45,
        R.raw.moderate_46,
        R.raw.moderate_47,
        R.raw.moderate_48,
        R.raw.moderate_49,
        R.raw.moderate_50,
        R.raw.moderate_51,
        R.raw.moderate_52,
        R.raw.moderate_53,
        R.raw.moderate_54,
        R.raw.moderate_55,
        R.raw.moderate_56,
        R.raw.moderate_57,
        R.raw.moderate_58,
        R.raw.moderate_59,
        R.raw.moderate_60,
        R.raw.moderate_61,
        R.raw.moderate_62,
        R.raw.moderate_63,
        R.raw.moderate_64,
        R.raw.moderate_65,
        R.raw.moderate_66,
        R.raw.moderate_67,
        R.raw.moderate_68,
        R.raw.moderate_69,
        R.raw.moderate_70,
        R.raw.moderate_71,
        R.raw.moderate_72,
        R.raw.moderate_73,
        R.raw.moderate_74,
        R.raw.moderate_75,
        R.raw.moderate_76,
        R.raw.moderate_77,
        R.raw.moderate_78,
        R.raw.moderate_79,
        R.raw.moderate_80,
        R.raw.moderate_81,
        R.raw.moderate_82,
        R.raw.moderate_83,
        R.raw.moderate_84,
        R.raw.moderate_85,
        R.raw.moderate_86,
        R.raw.moderate_87,
        R.raw.moderate_88
    )

    private inline fun ui(crossinline block: () -> Unit) {
        uiThreadHandler.post { block() }
    }

    private fun createSoundpool() = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.LOLLIPOP) {
        val usage = when (streamType) {
            AudioManager.STREAM_RING -> AudioAttributes.USAGE_NOTIFICATION_RINGTONE
            AudioManager.STREAM_ALARM -> android.media.AudioAttributes.USAGE_ALARM
            AudioManager.STREAM_NOTIFICATION -> android.media.AudioAttributes.USAGE_NOTIFICATION
            else -> android.media.AudioAttributes.USAGE_GAME
        }
        SoundPool.Builder()
                .setMaxStreams(maxStreams)
                .setAudioAttributes(AudioAttributes.Builder().setLegacyStreamType
                (streamType)
                        .setUsage(usage)
                        .build())
                .build()
    } else {
        SoundPool(maxStreams, streamType, 1)
    }.apply {
        setOnLoadCompleteListener { _, sampleId, status ->
            val resultCallback = loadingSoundsMap[sampleId]
            resultCallback?.let {
                ui {
                    if (status == 0) {
                        it.success(sampleId)
                    } else {
                        it.error("Loading failed", "Error code: $status", null)
                    }
                }
                loadingSoundsMap.remove(sampleId)

            }

        }

    }

    private val volumeSettings = mutableMapOf<Int, VolumeInfo>()


    private fun volumeSettingsForSoundId(soundId: Int): VolumeInfo =
            volumeSettings[soundId] ?: DEFAULT_VOLUME_INFO

    internal fun onMethodCall(call: MethodCall, result: Result) {

        when (call.method) {
            "load" -> {
                loadExecutor.execute {
                    try {
                        val arguments = call.arguments as Map<String, Any>
                        val soundData = arguments["rawSound"] as ByteArray
                        val priority = arguments["priority"] as Int
                        val tempFile = createTempFile(prefix = "sound", suffix = "pool", directory = context.cacheDir)
                        FileOutputStream(tempFile).use {
                            it.write(soundData)
                            tempFile.deleteOnExit()
                            val soundId = soundPool.load(tempFile.absolutePath, priority)
//                    result.success(soundId)
                            if (soundId > -1) {
                                loadingSoundsMap[soundId] = result
                            } else {
                                ui { result.success(soundId) }
                            }
                        }
                    } catch (t: Throwable) {
                        ui { result.error("Loading failure", t.message, null) }
                    }
                }
            }
            "loadUri" -> {
                loadExecutor.execute {
                    try {
                        val arguments = call.arguments as Map<String, Any>
                        val soundUri = arguments["uri"] as String
                        val priority = arguments["priority"] as Int
                        val soundId =
                                URI.create(soundUri).let { uri ->
                                    return@let if (uri.scheme == "content") {
                                        soundPool.load(context.contentResolver.openAssetFileDescriptor(Uri.parse(soundUri), "r"), 1)
                                    } else {
                                        val tempFile = createTempFile(prefix = "sound", suffix = "pool", directory = context.cacheDir)
                                        FileOutputStream(tempFile).use { out ->
                                            out.write(uri.toURL().readBytes())
                                        }
                                        tempFile.deleteOnExit()
                                        soundPool.load(tempFile.absolutePath, priority)
                                    }
                                }

                        if (soundId > -1) {
                            loadingSoundsMap[soundId] = result
                        } else {
                            ui { result.success(soundId) }
                        }
                    } catch (t: Throwable) {
                        ui { result.error("URI loading failure", t.message, null) }
                    }
                }
            }
            "loadNote" -> {
                loadExecutor.execute {
                    try {
                        val arguments = call.arguments as Map<String, Any>
                        val index = arguments["index"] as Int
                        val type = arguments["type"] as String

                        val soundId = when(type) {
                            "moderate" -> soundPool.load(context, moderateList[index], 1)
                            // "short" -> soundPool.load(context, shortList[index], 1)
                            else -> -1
                        } 

                        ui { result.success(soundId) }
                    } catch (t: Throwable) {
                        ui { result.error("Loading notes failure", t.message, null) }
                    }
                }
            }
            "release" -> {
                releaseSoundpool()
                soundPool = createSoundpool()
                result.success(null)
            }
            "play" -> {
                loadExecutor.execute {
                    val arguments = call.arguments as Map<String, Any>
                    val soundId: Int = (arguments["soundId"] as Int?)!!
                    val repeat: Int = arguments["repeat"] as Int? ?: 0
                    val rate: Double = arguments["rate"] as Double? ?: 1.0
                    val volumeLeft: Double = arguments["volumeLeft"] as Double? ?: 1.0
                    val volumeRight: Double = arguments["volumeRight"] as Double? ?: 1.0
                    // val volumeInfo = volumeSettingsForSoundId(soundId = soundId)
                    val streamId = soundPool.play(soundId, volumeLeft.toFloat(), volumeRight.toFloat(), 0,
                            repeat, rate.toFloat())
                    ui {
                        result.success(streamId)
                    }
                }

            }
            "pause" -> {
                val arguments = call.arguments as Map<String, Int>
                val streamId = arguments["streamId"]!!
                soundPool.pause(streamId)
                result.success(streamId)
            }
            "resume" -> {
                val arguments = call.arguments as Map<String, Int>
                val streamId = arguments["streamId"]!!
                soundPool.resume(streamId)
                result.success(streamId)
            }
            "stop" -> {
                val arguments = call.arguments as Map<String, Int>
                val streamId = arguments["streamId"]!!
                soundPool.stop(streamId)
                result.success(streamId)
            }
            "setVolume" -> {
                val arguments = call.arguments as Map<String, Any?>
                val streamId: Int? = arguments["streamId"] as Int?
                val soundId: Int? = arguments["soundId"] as Int?
                if (streamId == null && soundId == null) {
                    result.error("InvalidParameters", "Either 'streamId' or 'soundId' has to be " +
                            "passed", null)
                }
                val volumeLeft: Double = arguments["volumeLeft"]!! as Double
                val volumeRight: Double = arguments["volumeRight"]!! as Double

                streamId?.let {
                    soundPool.setVolume(it, volumeLeft.toFloat(), volumeRight.toFloat())
                }
                soundId?.let {
                    volumeSettings[it] = VolumeInfo(left = volumeLeft.toFloat(), right =
                    volumeRight.toFloat())
                }
                result.success(null)
            }
            "setRate" -> {

                val arguments = call.arguments as Map<String, Any?>
                val streamId: Int = arguments["streamId"]!! as Int
                val rate: Double = arguments["rate"] as Double? ?: 1.0
                soundPool.setRate(streamId, rate.toFloat())
                result.success(null)
            }
            else -> result.notImplemented()
        }
    }

    internal fun releaseSoundpool() {
        soundPool.release()
    }
}
