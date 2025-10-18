package com.health.openscale.core.bluetooth.modern

import android.annotation.SuppressLint
import android.content.Context
import com.health.openscale.core.bluetooth.BluetoothEvent
import com.health.openscale.core.bluetooth.data.ScaleUser
import com.health.openscale.core.facade.MeasurementFacade
import com.health.openscale.core.facade.SettingsFacade
import com.health.openscale.core.facade.UserFacade
import com.health.openscale.core.utils.LogManager

/**
 * Lightweight L2CAP adapter.
 *
 * This adapter's purpose is to:
 * - Wire a modern `ScaleDeviceHandler` with the required Transport / Callbacks / DriverSettings /
 * DataProvider objects.
 * - Mark the communicator as connected/disconnected from the app's perspective.
 *
 * Important:
 * - Handlers that require direct L2CAP sockets (e.g., `WiiBalanceBoardHandler`) should open and
 * manage their own sockets (for example via a `startL2cap(device, scope)` helper) from their
 * `onConnected`/`onDisconnected` hooks. This adapter intentionally does not attempt to open or
 * manage platform L2CAP sockets itself.
 */
class L2capScaleAdapter(
        context: Context,
        settingsFacade: SettingsFacade,
        measurementFacade: MeasurementFacade,
        userFacade: UserFacade,
        handler: ScaleDeviceHandler,
        private val profile: TuningProfile = TuningProfile.Balanced
) : ModernScaleAdapter(context, settingsFacade, measurementFacade, userFacade, handler) {

    // Keep visibility at least as wide as the super declaration.
    private val LOCAL_TAG: String = "L2capScaleAdapter"

    // Minimal transport implementation. Most L2CAP handlers will not use this and will
    // instead manage raw sockets themselves; keep methods no-op / logging to be safe.
    private val transportImpl =
            object : ScaleDeviceHandler.Transport {
                override fun setNotifyOn(service: java.util.UUID, characteristic: java.util.UUID) {
                    LogManager.v(LOCAL_TAG, "setNotifyOn called on L2CAP transport (no-op)")
                }

                override fun write(
                        service: java.util.UUID,
                        characteristic: java.util.UUID,
                        payload: ByteArray,
                        withResponse: Boolean
                ) {
                    LogManager.v(
                            LOCAL_TAG,
                            "write called on L2CAP transport (no-op) len=${payload.size}"
                    )
                }

                override fun read(service: java.util.UUID, characteristic: java.util.UUID) {
                    LogManager.v(LOCAL_TAG, "read called on L2CAP transport (no-op)")
                }

                override fun disconnect() {
                    LogManager.v(
                            LOCAL_TAG,
                            "transport.disconnect() requested (delegating to adapter)"
                    )
                    try {
                        this@L2capScaleAdapter.doDisconnect()
                    } catch (t: Throwable) {
                        LogManager.w(LOCAL_TAG, "transport.disconnect threw: ${t.message}", t)
                    }
                }

                override fun getPeripheral(): com.welie.blessed.BluetoothPeripheral? = null

                override fun hasCharacteristic(
                        service: java.util.UUID,
                        characteristic: java.util.UUID
                ): Boolean = false
            }

    @SuppressLint("MissingPermission")
    override fun doConnect(address: String, selectedUser: ScaleUser) {
        targetAddress = address
        _isConnecting.value = true
        try {
            // Create driver settings scoped to this device + handler namespace.
            val driverSettings =
                    FacadeDriverSettings(
                            facade = settingsFacade,
                            scope = scope,
                            deviceAddress = address,
                            handlerNamespace = handler::class.simpleName ?: "L2capHandler"
                    )

            // Attach handler to our minimal transport & callbacks
            handler.attach(transportImpl, appCallbacks, driverSettings, dataProvider)

            // Let the handler perform any connection-time setup (including starting L2CAP sockets
            // if it implements such a helper). Exceptions from the handler will be caught below.
            handler.handleConnected(selectedUser)

            // Mark connected for higher layers
            _isConnected.value = true
            _isConnecting.value = false
            _events.tryEmit(BluetoothEvent.Connected(handler::class.simpleName ?: "L2CAP", address))
            LogManager.i(LOCAL_TAG, "L2CAP handler attached and signalled connected for $address")
        } catch (t: Throwable) {
            LogManager.e(LOCAL_TAG, "L2CAP connect failed: ${t.message}", t)
            _events.tryEmit(
                    BluetoothEvent.ConnectionFailed(address, t.message ?: "L2CAP connect error")
            )
            // Best-effort cleanup on failure
            try {
                handler.handleDisconnected()
            } catch (_: Throwable) {}
            try {
                handler.detach()
            } catch (_: Throwable) {}
            _isConnecting.value = false
            _isConnected.value = false
        }
    }

    override fun doDisconnect() {
        val addr = targetAddress ?: "unknown"
        try {
            LogManager.i(LOCAL_TAG, "L2CAP disconnect requested for $addr")
            // Let handler close its own resources (sockets/readers) if implemented.
            try {
                handler.handleDisconnected()
            } catch (t: Throwable) {
                LogManager.w(LOCAL_TAG, "handler.handleDisconnected threw: ${t.message}", t)
            }
            try {
                handler.detach()
            } catch (t: Throwable) {
                LogManager.w(LOCAL_TAG, "handler.detach threw: ${t.message}", t)
            }
        } finally {
            // Reset adapter state and notify listeners
            _isConnected.value = false
            _isConnecting.value = false
            _events.tryEmit(BluetoothEvent.Disconnected(addr))
            LogManager.i(LOCAL_TAG, "L2CAP disconnected for $addr")
        }
    }
}
