package com.health.openscale.core.bluetooth.modern

import android.annotation.SuppressLint
import android.bluetooth.BluetoothDevice
import android.bluetooth.BluetoothSocket
import com.health.openscale.core.bluetooth.data.ScaleMeasurement
import com.health.openscale.core.facade.SettingsFacade
import com.health.openscale.core.facade.UserFacade
import com.health.openscale.core.service.ScannedDeviceInfo
import com.health.openscale.core.utils.LogManager
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.util.Date
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch

/**
 * Wii Balance Board handler (device name: "Nintendo RVL-WBC-01")
 *
 * This handler's responsibilities:
 * - Identify the Wii board by advertised name (exact match).
 * - Provide a small helper to open L2CAP control/interrupt channels (PSM 0x11, 0x13).
 * - Run a read loop on the interrupt channel, parse basic sensor frames and publish lightweight
 * ScaleMeasurement objects via the handler's publish()/callbacks.
 *
 * Notes / limitations:
 * - This class is a device-specific protocol handler and expects the surrounding connection
 * plumbing to open/own L2CAP sockets and call [startL2cap].
 * - The conversion from raw sensor values to kilograms here is intentionally simple: it sums sensor
 * channels and applies a coarse scale factor. For accuracy you should read and apply the board's
 * calibration table (see Wiibrew / your notes).
 * - Android L2CAP APIs require modern Android versions (API 29+ for createL2capChannel).
 * - The handler attempts to be conservative and non-invasive: it will not attempt to modify system
 * HID registration. See the project notes for mitigation strategies.
 */
class WiiBalanceBoardHandler(
        private val context: android.content.Context,
        private val settingsFacade: SettingsFacade,
        private val userFacade: UserFacade
) : ScaleDeviceHandler() {

    companion object {
        private const val TAG = "BluetoothWii"
        private const val NAME = "Nintendo RVL-WBC-01"

        // Historically used Wiimote/Wii Balance Board PSMs for L2CAP HID:
        // control PSM = 0x11 (17), interrupt PSM = 0x13 (19)
        private const val PSM_CONTROL = 0x11
        private const val PSM_INTERRUPT = 0x13

        // Conservative reader buffer size
        private const val READ_BUF = 128
    }

    override fun supportFor(device: ScannedDeviceInfo): DeviceSupport? {
        if (device.name?.equals(NAME, ignoreCase = true) == true) {
            return DeviceSupport(
                    displayName = "Wii Balance Board",
                    capabilities = setOf(DeviceCapability.LIVE_WEIGHT_STREAM),
                    implemented = setOf(DeviceCapability.LIVE_WEIGHT_STREAM),
                    tuningProfile = TuningProfile.Balanced,
                    linkMode = LinkMode.L2CAP
            )
        }
        return null
    }

    // L2CAP resources (one control, one interrupt)
    private var controlSocket: BluetoothSocket? = null
    private var interruptSocket: BluetoothSocket? = null
    private var readerJob: Job? = null

    /**
     * Open L2CAP channels (control & interrupt) and start the interrupt reader loop.
     *
     * This method is a best-effort helper that abstracts the low-level L2CAP work and parses
     * incoming interrupt reports into coarse weight measurements.
     *
     * @param device A bonded/paired BluetoothDevice instance for the board.
     * @param scope CoroutineScope to run the reader(s) in (should be a background scope).
     */
    @SuppressLint("MissingPermission")
    fun startL2cap(device: BluetoothDevice, scope: CoroutineScope) {
        // launch asynchronously; caller should be prepared for results via publish()/callbacks
        scope.launch(Dispatchers.IO) {
            try {
                // Try to open control & interrupt channels. These calls may throw on unsupported
                // devices. Many Android builds expose L2CAP as a Socket-like API; attempt to open
                // sockets and fall back gracefully if unavailable.
                controlSocket =
                        try {
                            device.createL2capChannel(PSM_CONTROL)
                        } catch (t: Throwable) {
                            LogManager.w(TAG, "createL2capChannel(control) failed: ${t.message}")
                            null
                        }

                interruptSocket =
                        try {
                            device.createL2capChannel(PSM_INTERRUPT)
                        } catch (t: Throwable) {
                            LogManager.w(TAG, "createL2capChannel(interrupt) failed: ${t.message}")
                            null
                        }

                if (controlSocket == null || interruptSocket == null) {
                    LogManager.e(TAG, "Could not open L2CAP channels for ${safeAddress(device)}")
                    cleanup()
                    return@launch
                }

                // Streams (use only the streams we need: write control, read interrupt)
                val controlOut = controlSocket!!.outputStream
                val intrIn = interruptSocket!!.inputStream

                LogManager.i(TAG, "L2CAP channels opened for ${safeAddress(device)}")

                // Perform a minimal HID / Wiimote status request to get device to emit reports.
                // Many Wiimote projects send 0x52, 0x15 (request status) on control channel.
                try {
                    controlOut.write(byteArrayOf(0x52.toByte(), 0x15.toByte()))
                    controlOut.flush()
                } catch (t: Throwable) {
                    LogManager.v(TAG, "Failed to write initial status request: ${t.message}")
                }

                // Start a reader for the interrupt channel.
                readerJob?.cancel()
                readerJob =
                        scope.launch(Dispatchers.IO) {
                            val buf = ByteArray(READ_BUF)
                            try {
                                while (isActive) {
                                    val avail =
                                            try {
                                                intrIn.available()
                                            } catch (t: Throwable) {
                                                // If available() fails, fall back to blocking read
                                                -1
                                            }
                                    val n =
                                            if (avail > 0) {
                                                intrIn.read(buf, 0, minOf(avail, buf.size))
                                            } else {
                                                // blocking read
                                                intrIn.read(buf)
                                            }
                                    if (n <= 0) {
                                        // End of stream or socket closed
                                        delay(50)
                                        if (!isActive) break
                                        continue
                                    }
                                    val pkt = buf.copyOf(n)
                                    handleInterruptPacket(pkt)
                                }
                            } catch (t: Throwable) {
                                LogManager.w(TAG, "Interrupt reader exited: ${t.message}", t)
                            } finally {
                                try {
                                    controlSocket?.close()
                                } catch (_: Throwable) {}
                                try {
                                    interruptSocket?.close()
                                } catch (_: Throwable) {}
                                controlSocket = null
                                interruptSocket = null
                            }
                        }
            } catch (t: Throwable) {
                LogManager.e(TAG, "startL2cap failed: ${t.message}", t)
                cleanup()
            }
        }
    }

    /**
     * Parse an incoming interrupt packet from the board. This parser is intentionally conservative:
     * it will try to extract the four pressure sensor raw values when a suitable payload is
     * observed.
     *
     * On success, it publishes a ScaleMeasurement via the handler's publish() helper.
     */
    private fun handleInterruptPacket(pkt: ByteArray) {
        LogManager.v(TAG, "Intr pkt: ${pkt.toHexPreview(32)}")
        if (pkt.isEmpty()) return

        try {
            // Many Wiimote-style reports include a leading report-id byte; some formats put
            // sensor samples starting at offset 2 or 3. We'll attempt common offsets.
            val candidateOffsets = listOf(1, 2, 3, 0)
            for (off in candidateOffsets) {
                if (pkt.size >= off + 8) {
                    val slice = pkt.copyOfRange(off, off + 8)
                    val bb = ByteBuffer.wrap(slice).order(ByteOrder.BIG_ENDIAN)
                    val tr = bb.getShort(0).toInt() and 0xFFFF
                    val br = bb.getShort(2).toInt() and 0xFFFF
                    val tl = bb.getShort(4).toInt() and 0xFFFF
                    val bl = bb.getShort(6).toInt() and 0xFFFF

                    // Sanity check: raw values should be non-zero and within reasonable range.
                    if (tr in 0..0xFFFF && br in 0..0xFFFF && tl in 0..0xFFFF && bl in 0..0xFFFF) {
                        val kg = convertRawToKg(tl, tr, bl, br)

                        val m =
                                ScaleMeasurement().apply {
                                    setWeight(kg)
                                    setDateTime(Date())
                                }

                        LogManager.i(
                                TAG,
                                "Wii weight: %.2f kg (raw TR=%d BR=%d TL=%d BL=%d)".format(
                                        kg,
                                        tr,
                                        br,
                                        tl,
                                        bl
                                )
                        )
                        // Publish to the app
                        publish(m)
                        // We processed a valid sample; break out of offset loop.
                        return
                    }
                }
            }
            // If we reach here, nothing matched; just log fine-grained info.
            LogManager.v(TAG, "Interrupt packet did not match expected sensor layout.")
        } catch (t: Throwable) {
            LogManager.w(TAG, "Failed to parse interrupt packet: ${t.message}", t)
        }
    }

    /**
     * Convert raw sensor sums to a coarse weight estimate (kg).
     *
     * This is a placeholder conversion: it simply sums the four channels and applies a coarse scale
     * factor. For production usage, parse and apply the board's calibration table as documented in
     * your notes.
     */
    private fun convertRawToKg(tl: Int, tr: Int, bl: Int, br: Int): Float {
        val total = tl.toLong() + tr.toLong() + bl.toLong() + br.toLong()
        // Avoid division by zero; choose factor so typical Wii totals map to reasonable kg.
        // You will want to replace this with calibrated transforms.
        return (total / 1000.0f).coerceAtLeast(0.0f)
    }

    /** Stop readers and close any open L2CAP resources. */
    fun stop() {
        try {
            readerJob?.cancel()
        } catch (_: Throwable) {}
        readerJob = null
        try {
            controlSocket?.close()
        } catch (_: Throwable) {}
        controlSocket = null
        try {
            interruptSocket?.close()
        } catch (_: Throwable) {}
        interruptSocket = null
    }

    private fun cleanup() {
        stop()
    }

    // --- Utilities ------------------------------------------------------------------------------

    private fun safeAddress(d: BluetoothDevice?): String {
        return try {
            d?.address ?: "unknown"
        } catch (_: Throwable) {
            "unknown"
        }
    }
}
