package com.musify.mu.voice

import android.content.Context
import android.media.MediaRecorder
 
import java.util.concurrent.atomic.AtomicBoolean
import android.os.Handler
import android.os.Looper
import org.webrtc.AudioSource
import org.webrtc.AudioTrack
import org.webrtc.DefaultVideoDecoderFactory
import org.webrtc.DefaultVideoEncoderFactory
import org.webrtc.EglBase
import org.webrtc.MediaConstraints
import org.webrtc.MediaStream
import org.webrtc.MediaStreamTrack
import org.webrtc.PeerConnection
import org.webrtc.PeerConnectionFactory
import org.webrtc.RtpSender
import org.webrtc.RtpTransceiver
import org.webrtc.SdpObserver
import org.webrtc.SessionDescription
import org.webrtc.audio.JavaAudioDeviceModule

/**
 * WebRtcCapture sets up a minimal WebRTC audio pipeline on Android that:
 * - Captures microphone audio via JavaAudioDeviceModule
 * - Enables WebRTC software audio processing (AEC/NS/AGC/HPF) via constraints
 * - Emits 20ms frames at 16 kHz mono (320 samples) via onFrame callback
 *
 * The implementation creates a local PeerConnection and AudioTrack to ensure
 * the audio device module starts recording. No network connection is required.
 */
class WebRtcCapture(
    private val context: Context,
    private val onFrame: (ShortArray) -> Unit,
    private val onError: (String) -> Unit = {}
) {
    private var eglBase: EglBase? = null
    private var peerConnectionFactory: PeerConnectionFactory? = null
    private var audioDeviceModule: JavaAudioDeviceModule? = null
    private var audioSource: AudioSource? = null
    private var audioTrack: AudioTrack? = null
    private var peerConnection: PeerConnection? = null
    private var loopbackPeerConnection: PeerConnection? = null
    private var audioSender: RtpSender? = null
    private val mainHandler = Handler(Looper.getMainLooper())
    private var receivedSamples = false

    private val started = AtomicBoolean(false)

    // 20 ms at 16 kHz mono
    private val targetSampleRate = 16000
    private val targetFrameSamples = 320
    private val frameAccumulator = ShortArray(targetFrameSamples * 4)
    private var accLen = 0

    fun start() {
        if (started.getAndSet(true)) return
        try {
            PeerConnectionFactory.initialize(
                PeerConnectionFactory.InitializationOptions.builder(context).createInitializationOptions()
            )

            eglBase = EglBase.create()
            val encoderFactory = DefaultVideoEncoderFactory(eglBase!!.eglBaseContext, true, true)
            val decoderFactory = DefaultVideoDecoderFactory(eglBase!!.eglBaseContext)

            val samplesCallback = object : JavaAudioDeviceModule.SamplesReadyCallback {
                override fun onWebRtcAudioRecordSamplesReady(samples: JavaAudioDeviceModule.AudioSamples) {
                    try {
                        receivedSamples = true
                        handleSamples(samples)
                    } catch (t: Throwable) {
                        onError("APM sample handling failed: ${t.message}")
                    }
                }
            }

            audioDeviceModule = JavaAudioDeviceModule.builder(context)
                .setUseHardwareAcousticEchoCanceler(false)
                .setUseHardwareNoiseSuppressor(false)
                .setAudioSource(MediaRecorder.AudioSource.VOICE_RECOGNITION)
                .setSamplesReadyCallback(samplesCallback)
                .createAudioDeviceModule()

            peerConnectionFactory = PeerConnectionFactory.builder()
                .setAudioDeviceModule(audioDeviceModule)
                .setVideoEncoderFactory(encoderFactory)
                .setVideoDecoderFactory(decoderFactory)
                .createPeerConnectionFactory()

            val constraints = MediaConstraints().apply {
                mandatory.add(MediaConstraints.KeyValuePair("googEchoCancellation", "true"))
                mandatory.add(MediaConstraints.KeyValuePair("googAutoGainControl", "true"))
                mandatory.add(MediaConstraints.KeyValuePair("googNoiseSuppression", "true"))
                mandatory.add(MediaConstraints.KeyValuePair("googHighpassFilter", "true"))
            }

            audioSource = peerConnectionFactory!!.createAudioSource(constraints)
            audioTrack = peerConnectionFactory!!.createAudioTrack("ARDAMSa0", audioSource)
            audioTrack!!.setEnabled(true)

            val rtcConfig = PeerConnection.RTCConfiguration(emptyList())
            peerConnection = peerConnectionFactory!!.createPeerConnection(
                rtcConfig,
                object : PeerConnection.Observer {
                    override fun onSignalingChange(newState: PeerConnection.SignalingState) {}
                    override fun onIceConnectionChange(newState: PeerConnection.IceConnectionState) {}
                    override fun onIceConnectionReceivingChange(receiving: Boolean) {}
                    override fun onIceGatheringChange(newState: PeerConnection.IceGatheringState) {}
                    override fun onIceCandidate(candidate: org.webrtc.IceCandidate) {
                        try { loopbackPeerConnection?.addIceCandidate(candidate) } catch (_: Throwable) {}
                    }
                    override fun onIceCandidatesRemoved(candidates: Array<out org.webrtc.IceCandidate>) {}
                    override fun onAddStream(stream: MediaStream) {}
                    override fun onRemoveStream(stream: MediaStream) {}
                    override fun onDataChannel(dc: org.webrtc.DataChannel) {}
                    override fun onRenegotiationNeeded() {}
                    override fun onAddTrack(receiver: org.webrtc.RtpReceiver, streams: Array<out MediaStream>) {}
                }
            )

            // Create a loopback peer connection to complete SDP handshake locally
            loopbackPeerConnection = peerConnectionFactory!!.createPeerConnection(
                rtcConfig,
                object : PeerConnection.Observer {
                    override fun onSignalingChange(newState: PeerConnection.SignalingState) {}
                    override fun onIceConnectionChange(newState: PeerConnection.IceConnectionState) {}
                    override fun onIceConnectionReceivingChange(receiving: Boolean) {}
                    override fun onIceGatheringChange(newState: PeerConnection.IceGatheringState) {}
                    override fun onIceCandidate(candidate: org.webrtc.IceCandidate) {
                        try { peerConnection?.addIceCandidate(candidate) } catch (_: Throwable) {}
                    }
                    override fun onIceCandidatesRemoved(candidates: Array<out org.webrtc.IceCandidate>) {}
                    override fun onAddStream(stream: MediaStream) {}
                    override fun onRemoveStream(stream: MediaStream) {}
                    override fun onDataChannel(dc: org.webrtc.DataChannel) {}
                    override fun onRenegotiationNeeded() {}
                    override fun onAddTrack(receiver: org.webrtc.RtpReceiver, streams: Array<out MediaStream>) {}
                }
            )

            try { loopbackPeerConnection?.addTransceiver(MediaStreamTrack.MediaType.AUDIO) } catch (_: Throwable) {}

            // Unified Plan: addTrack directly
            audioSender = peerConnection!!.addTrack(audioTrack, listOf("ARDAMS"))

            // Perform local loopback SDP handshake to ensure audio capture starts
            val offerConstraints = MediaConstraints().apply {
                mandatory.add(MediaConstraints.KeyValuePair("OfferToReceiveAudio", "false"))
                mandatory.add(MediaConstraints.KeyValuePair("OfferToReceiveVideo", "false"))
            }
            peerConnection!!.createOffer(object : SdpObserver {
                override fun onCreateSuccess(offer: SessionDescription) {
                    try {
                        peerConnection?.setLocalDescription(object : SdpObserver {
                            override fun onSetSuccess() {
                                try {
                                    loopbackPeerConnection?.setRemoteDescription(object : SdpObserver {
                                        override fun onSetSuccess() {
                                            loopbackPeerConnection?.createAnswer(object : SdpObserver {
                                                override fun onCreateSuccess(answer: SessionDescription) {
                                                    try {
                                                        loopbackPeerConnection?.setLocalDescription(object : SdpObserver {
                                                            override fun onSetSuccess() {
                                                                peerConnection?.setRemoteDescription(object : SdpObserver {
                                                                    override fun onSetSuccess() {}
                                                                    override fun onSetFailure(p0: String?) {}
                                                                    override fun onCreateSuccess(p0: SessionDescription?) {}
                                                                    override fun onCreateFailure(p0: String?) {}
                                                                }, answer)
                                                            }
                                                            override fun onSetFailure(p0: String?) {}
                                                            override fun onCreateSuccess(p0: SessionDescription?) {}
                                                            override fun onCreateFailure(p0: String?) {}
                                                        }, answer)
                                                    } catch (_: Throwable) {}
                                                }
                                                override fun onCreateFailure(p0: String?) {}
                                                override fun onSetSuccess() {}
                                                override fun onSetFailure(p0: String?) {}
                                            }, MediaConstraints())
                                        }
                                        override fun onSetFailure(p0: String?) {}
                                        override fun onCreateSuccess(p0: SessionDescription?) {}
                                        override fun onCreateFailure(p0: String?) {}
                                    }, offer)
                                } catch (_: Throwable) {}
                            }
                            override fun onSetFailure(p0: String?) {}
                            override fun onCreateSuccess(p0: SessionDescription?) {}
                            override fun onCreateFailure(p0: String?) {}
                        }, offer)
                    } catch (_: Throwable) {}
                }
                override fun onCreateFailure(error: String?) {}
                override fun onSetSuccess() {}
                override fun onSetFailure(p0: String?) {}
            }, offerConstraints)

            // If no samples arrive shortly after starting, notify via onError so caller can fallback
            receivedSamples = false
            mainHandler.postDelayed({
                if (started.get() && !receivedSamples) {
                    onError("WebRTC audio processing produced no samples (timeout)")
                }
            }, 2000)
        } catch (t: Throwable) {
            onError("WebRTC init failed: ${t.message}")
            stop()
        }
    }

    fun stop() {
        if (!started.getAndSet(false)) return
        try { mainHandler.removeCallbacksAndMessages(null) } catch (_: Throwable) {}
        try { audioSender?.setTrack(null, false) } catch (_: Throwable) {}
        try { peerConnection?.close() } catch (_: Throwable) {}
        try { loopbackPeerConnection?.close() } catch (_: Throwable) {}
        try { audioTrack?.dispose() } catch (_: Throwable) {}
        try { audioSource?.dispose() } catch (_: Throwable) {}
        try { peerConnectionFactory?.dispose() } catch (_: Throwable) {}
        try { audioDeviceModule?.release() } catch (_: Throwable) {}
        try { eglBase?.release() } catch (_: Throwable) {}
        audioSender = null
        peerConnection = null
        loopbackPeerConnection = null
        audioTrack = null
        audioSource = null
        peerConnectionFactory = null
        audioDeviceModule = null
        eglBase = null
        accLen = 0
    }

    private fun handleSamples(samples: JavaAudioDeviceModule.AudioSamples) {
        val sampleRate = samples.sampleRate
        val channels = samples.channelCount
        val bytes = samples.data
        val numShorts = bytes.size / 2
        val tmp = ShortArray(numShorts)
        var bIndex = 0
        for (i in 0 until numShorts) {
            val lo = bytes[bIndex].toInt() and 0xFF
            val hi = bytes[bIndex + 1].toInt() and 0xFF
            tmp[i] = (((hi shl 8) or lo) and 0xFFFF).toShort()
            bIndex += 2
        }

        // Downmix to mono if needed
        val mono = if (channels == 1) tmp else downmixToMono(tmp, channels)

        // Resample to 16 kHz if needed
        val at16k = if (sampleRate == targetSampleRate) mono else resampleLinear(mono, sampleRate, targetSampleRate)

        // Chunk into 20 ms frames and emit
        var offset = 0
        while (offset < at16k.size) {
            val copy = kotlin.math.min(at16k.size - offset, frameAccumulator.size - accLen)
            System.arraycopy(at16k, offset, frameAccumulator, accLen, copy)
            accLen += copy
            offset += copy
            while (accLen >= targetFrameSamples) {
                val out = ShortArray(targetFrameSamples)
                System.arraycopy(frameAccumulator, 0, out, 0, targetFrameSamples)
                onFrame(out)
                // shift leftover
                val remain = accLen - targetFrameSamples
                if (remain > 0) {
                    System.arraycopy(frameAccumulator, targetFrameSamples, frameAccumulator, 0, remain)
                }
                accLen = remain
            }
        }
    }

    private fun downmixToMono(input: ShortArray, channels: Int): ShortArray {
        if (channels <= 1) return input
        val frames = input.size / channels
        val out = ShortArray(frames)
        var j = 0
        for (i in 0 until frames) {
            var sum = 0
            for (c in 0 until channels) {
                sum += input[j++].toInt()
            }
            out[i] = (sum / channels).coerceIn(Short.MIN_VALUE.toInt(), Short.MAX_VALUE.toInt()).toShort()
        }
        return out
    }

    private fun resampleLinear(input: ShortArray, inRate: Int, outRate: Int): ShortArray {
        if (inRate == outRate) return input
        val ratio = outRate.toDouble() / inRate.toDouble()
        val outLength = kotlin.math.max(1, (input.size * ratio).toInt())
        val out = ShortArray(outLength)
        var srcPos = 0.0
        for (i in 0 until outLength) {
            val idx = srcPos.toInt()
            val frac = srcPos - idx
            val s0 = input[idx].toInt()
            val s1 = input[kotlin.math.min(idx + 1, input.size - 1)].toInt()
            val interp = (s0 + (s1 - s0) * frac).toInt()
            out[i] = interp.coerceIn(Short.MIN_VALUE.toInt(), Short.MAX_VALUE.toInt()).toShort()
            srcPos += 1.0 / ratio
        }
        return out
    }
}

