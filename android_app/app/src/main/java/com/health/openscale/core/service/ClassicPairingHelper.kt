package com.health.openscale.core.service

import android.annotation.SuppressLint
import android.bluetooth.BluetoothDevice
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import com.health.openscale.core.utils.LogManager
import java.lang.reflect.Method
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeout

/**
 * Helper for performing best-effort programmatic Bluetooth Classic pairing (createBond +
 * PIN/confirm).
 *
 * Notes:
 * - This is best-effort: many OEMs / Android versions restrict programmatic PIN entry and
 * auto-confirmation.
 * - The helper listens for ACTION_PAIRING_REQUEST and ACTION_BOND_STATE_CHANGED and will attempt to
 * set the PIN and confirm the pairing using reflection where available.
 * - Callers should ensure the app has the required Bluetooth runtime permissions before invoking.
 */
class ClassicPairingHelper(private val context: Context) {

    companion object {
        private const val TAG = "ClassicPairingHelper"
    }

    /**
     * Attempts to pair (bond) with the provided device.
     *
     * @param device BluetoothDevice to pair with.
     * @param pin Optional PIN to apply during pairing (e.g., "0000"). Best-effort only.
     * @param timeoutMs Timeout for the whole pairing operation.
     * @return true if the device is bonded at the end of the operation; false otherwise.
     */
    @SuppressLint("MissingPermission")
    suspend fun pairDevice(
            device: BluetoothDevice,
            pin: String? = null,
            timeoutMs: Long = 15_000L
    ): Boolean {
        // Fast path: already bonded
        try {
            if (device.bondState == BluetoothDevice.BOND_BONDED) {
                LogManager.d(TAG, "Device ${safeAddress(device)} already bonded")
                return true
            }
        } catch (t: Throwable) {
            // ignore access exceptions; continue trying
            LogManager.w(TAG, "Error checking bond state: ${t.message}", t)
        }

        val result = CompletableDeferred<Boolean>()

        val receiver =
                object : BroadcastReceiver() {
                    override fun onReceive(ctx: Context?, intent: Intent?) {
                        if (intent == null) return
                        val action = intent.action ?: return

                        when (action) {
                            BluetoothDevice.ACTION_BOND_STATE_CHANGED -> {
                                val d: BluetoothDevice? =
                                        try {
                                            intent.getParcelableExtra(BluetoothDevice.EXTRA_DEVICE)
                                        } catch (t: Throwable) {
                                            null
                                        }
                                if (d == null) return
                                if (!addressesEqual(d, device)) return

                                when (d.bondState) {
                                    BluetoothDevice.BOND_BONDED -> {
                                        if (!result.isCompleted) {
                                            LogManager.i(TAG, "Bonded with ${safeAddress(d)}")
                                            result.complete(true)
                                        }
                                    }
                                    BluetoothDevice.BOND_NONE -> {
                                        if (!result.isCompleted) {
                                            LogManager.w(
                                                    TAG,
                                                    "Bonding failed or removed for ${safeAddress(d)}"
                                            )
                                            result.complete(false)
                                        }
                                    }
                                }
                            }
                            BluetoothDevice.ACTION_PAIRING_REQUEST -> {
                                val d: BluetoothDevice? =
                                        try {
                                            intent.getParcelableExtra(BluetoothDevice.EXTRA_DEVICE)
                                        } catch (t: Throwable) {
                                            null
                                        }
                                if (d == null) return
                                if (!addressesEqual(d, device)) return

                                LogManager.i(
                                        TAG,
                                        "PAIRING_REQUEST from ${safeAddress(d)}; attempting best-effort handling"
                                )

                                // Try to suppress the system pairing UI when possible by aborting
                                // the ordered broadcast.
                                // This is best-effort and may be ignored on many OEM/Android
                                // builds.
                                try {
                                    abortBroadcast()
                                    LogManager.d(
                                            TAG,
                                            "Aborted pairing broadcast for ${safeAddress(d)}"
                                    )
                                } catch (t: Throwable) {
                                    LogManager.v(
                                            TAG,
                                            "abortBroadcast() not available or failed: ${t.message}"
                                    )
                                }

                                try {
                                    // Attempt to apply PIN / passkey then confirm pairing, with
                                    // extra fallbacks.
                                    pin?.let { trySetPin(d, it) }
                                    pin?.let { trySetPasskey(d, it) }
                                    tryConfirmPairing(d)

                                    // Some device stacks require cancelling user input or bond
                                    // process after confirming.
                                    tryCancelPairingUserInput(d)
                                    tryCancelBondProcess(d)
                                } catch (t: Throwable) {
                                    LogManager.w(
                                            TAG,
                                            "Error while handling pairing request: ${t.message}",
                                            t
                                    )
                                }
                            }
                        }
                    }
                }

        val filter =
                IntentFilter().apply {
                    addAction(BluetoothDevice.ACTION_BOND_STATE_CHANGED)
                    addAction(BluetoothDevice.ACTION_PAIRING_REQUEST)
                    // Request high priority so we have a better chance of receiving the ordered
                    // pairing
                    // broadcast ahead of the system UI. This is best-effort and may be ignored.
                    try {
                        setPriority(1000)
                    } catch (t: Throwable) {
                        LogManager.v(TAG, "Failed to set IntentFilter priority: ${t.message}")
                    }
                }

        try {
            context.registerReceiver(receiver, filter)
        } catch (t: Throwable) {
            LogManager.w(TAG, "Failed to register pairing receiver: ${t.message}", t)
            // Continue — we can still attempt createBond() and hope system pairing UI appears.
        }

        try {
            // Initiate bonding. Some devices require user confirmation in system UI.
            val started =
                    try {
                        device.createBond()
                    } catch (t: Throwable) {
                        LogManager.w(TAG, "createBond() threw: ${t.message}", t)
                        false
                    }

            if (!started) {
                LogManager.w(
                        TAG,
                        "createBond() returned false for ${safeAddress(device)}; still waiting for bond events"
                )
            } else {
                LogManager.i(TAG, "createBond() initiated for ${safeAddress(device)}")
            }

            // Wait for bond result or timeout
            return withContext(Dispatchers.IO) {
                try {
                    withTimeout(timeoutMs) { result.await() }
                } catch (t: Throwable) {
                    LogManager.w(TAG, "Pairing timed out or failed: ${t.message}")
                    false
                }
            }
        } finally {
            try {
                context.unregisterReceiver(receiver)
            } catch (t: Throwable) {
                // Ignore unregister errors
            }
        }
    }

    // --- Reflection helpers (best-effort) ----------------------------------------------------

    private fun trySetPin(device: BluetoothDevice, pin: String) {
        try {
            // Preferred signature: setPin(byte[])
            val method: Method? = device.javaClass.getMethod("setPin", ByteArray::class.java)
            method?.invoke(device, pin.toByteArray(Charsets.UTF_8))
            LogManager.d(TAG, "Invoked setPin(byte[]) on ${safeAddress(device)}")
            return
        } catch (t: Throwable) {
            // fallthrough to try String-based variants
            LogManager.v(TAG, "setPin(byte[]) not available: ${t.message}")
        }

        try {
            // Some older/hacked implementations expose setPin(String)
            val methodStr: Method? = device.javaClass.getMethod("setPin", String::class.java)
            methodStr?.invoke(device, pin)
            LogManager.d(TAG, "Invoked setPin(String) on ${safeAddress(device)}")
            return
        } catch (t: Throwable) {
            LogManager.v(TAG, "setPin(String) not available: ${t.message}")
        }

        // Some implementations require using the static helper on BluetoothDevice class (rare).
        LogManager.d(TAG, "No setPin reflection method available for ${safeAddress(device)}")
    }

    private fun tryConfirmPairing(device: BluetoothDevice) {
        try {
            val m: Method? =
                    device.javaClass.getMethod(
                            "setPairingConfirmation",
                            Boolean::class.javaPrimitiveType
                    )
            m?.invoke(device, true)
            LogManager.d(TAG, "Invoked setPairingConfirmation(true) on ${safeAddress(device)}")
            return
        } catch (t: Throwable) {
            LogManager.v(TAG, "setPairingConfirmation not available: ${t.message}")
        }

        try {
            // Some devices provide a method to confirm pairing without args
            val m2: Method? = device.javaClass.getMethod("confirmPairing")
            m2?.invoke(device)
            LogManager.d(TAG, "Invoked confirmPairing() on ${safeAddress(device)}")
        } catch (t: Throwable) {
            LogManager.v(TAG, "confirmPairing() not available: ${t.message}")
        }
    }

    /** Best-effort attempts to set a numeric passkey. Some stacks expose int or String variants. */
    private fun trySetPasskey(device: BluetoothDevice, passkey: String) {
        try {
            val m: Method? = device.javaClass.getMethod("setPasskey", Int::class.javaPrimitiveType)
            val pk = passkey.toIntOrNull()
            if (pk != null) {
                m?.invoke(device, pk)
                LogManager.d(TAG, "Invoked setPasskey(int) on ${safeAddress(device)}")
                return
            }
        } catch (t: Throwable) {
            LogManager.v(TAG, "setPasskey(int) not available or failed: ${t.message}")
        }

        try {
            val m2: Method? = device.javaClass.getMethod("setPasskey", String::class.java)
            m2?.invoke(device, passkey)
            LogManager.d(TAG, "Invoked setPasskey(String) on ${safeAddress(device)}")
            return
        } catch (t: Throwable) {
            LogManager.v(TAG, "setPasskey(String) not available: ${t.message}")
        }

        // No passkey helper available
        LogManager.v(TAG, "No setPasskey reflection method available for ${safeAddress(device)}")
    }

    /**
     * Some vendor stacks expose a cancelPairingUserInput() method which can be helpful to close
     * system UI or finish pairing flows after we've programmatically applied PIN/confirmation.
     */
    private fun tryCancelPairingUserInput(device: BluetoothDevice) {
        try {
            val m: Method? = device.javaClass.getMethod("cancelPairingUserInput")
            m?.invoke(device)
            LogManager.d(TAG, "Invoked cancelPairingUserInput() on ${safeAddress(device)}")
        } catch (t: Throwable) {
            LogManager.v(TAG, "cancelPairingUserInput() not available: ${t.message}")
        }
    }

    /**
     * Some implementations expose cancelBondProcess() to abort a pending bond; try it as a
     * fallback.
     */
    private fun tryCancelBondProcess(device: BluetoothDevice) {
        try {
            val m: Method? = device.javaClass.getMethod("cancelBondProcess")
            m?.invoke(device)
            LogManager.d(TAG, "Invoked cancelBondProcess() on ${safeAddress(device)}")
        } catch (t: Throwable) {
            LogManager.v(TAG, "cancelBondProcess() not available: ${t.message}")
        }
    }

    // --- Utilities ---------------------------------------------------------------------------

    private fun addressesEqual(a: BluetoothDevice, b: BluetoothDevice): Boolean {
        return try {
            val aa = a.address
            val bb = b.address
            !aa.isNullOrEmpty() && aa.equals(bb, ignoreCase = true)
        } catch (t: Throwable) {
            false
        }
    }

    private fun safeAddress(d: BluetoothDevice): String {
        return try {
            d.address
        } catch (_: Throwable) {
            "unknown"
        }
    }
}
