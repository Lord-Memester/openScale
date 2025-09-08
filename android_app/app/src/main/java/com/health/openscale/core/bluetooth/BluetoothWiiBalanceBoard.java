package com.health.openscale.core.bluetooth;

import static com.health.openscale.core.utils.Converters.toCentimeter;

import android.bluetooth.BluetoothAdapter;
import android.bluetooth.BluetoothDevice;
import android.bluetooth.BluetoothSocket;
import android.content.Context;
import android.util.Log;

import com.health.openscale.core.OpenScale;
import com.health.openscale.core.datatypes.ScaleMeasurement;
import com.health.openscale.core.datatypes.ScaleUser;
import com.health.openscale.core.utils.BluetoothGattUuid;
import com.welie.blessed.BluetoothPeripheral;

import java.lang.reflect.Constructor;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Calendar;
import java.util.Date;
import java.util.UUID;
import java.time.LocalDateTime;

import timber.log.Timber;

public class BluetoothWiiBalanceBoard extends BluetoothCommunication {
    private static final String TAG = "BluetoothWiiBalanceBoard";

    // L2CAP PSMs (ports) for Wii Balance Board
    private static final int L2CAP_PSM_INPUT = 0x13;
    private static final int L2CAP_PSM_OUTPUT = 0x11;

    // Commands and Reports from BalanceBoard.java
    private static final byte CMD_LED = 0x11;
    private static final byte CMD_REPORT_TYPE = 0x12;
    private static final byte CMD_CTRL_STATUS = 0x15;
    private static final byte CMD_WRITE_DATA = 0x16;
    private static final byte CMD_READ_DATA = 0x17;

    private static final byte RPT_CTRL_STATUS = 0x20;
    private static final byte RPT_READ = 0x21;
    private static final byte RPT_WRITE = 0x22;
    private static final byte RPT_BTN = 0x30;
    private static final byte RPT_BTN_EXP = 0x34;

    private static final int MEM_OFFSET_CALIBRATION = 0x16;
    private static final int EXP_MEM_ENABLE = 0x04A40040;
    private static final int EXP_MEM_CALIBR = 0x04A40020;
    private static final int EXP_ID_WIIBOARD = 0xA4200402;
    private static final short EXP_HANDSHAKE_LEN = 224;

    private ScaleUser user;
    private Data dat = new Data();
    private boolean haveExpansion = false;
    private boolean receivedStatus = false;
    private ArrayList<ReadRequest> readRequests = new ArrayList<>();
    private Object lock = new Object(); // For synchronizing access to shared state

    private InputThread inputThread;
    private OutputThread outputThread;
    private KickStatusThread kickStatusThread;
    private BlinkThread blinkThread;

    public BluetoothWiiBalanceBoard(Context context) {
        super(context);
    }

    @Override
    public String driverName() {
        return "Wii Balance Board";
    }

    @Override
    public void onBluetoothNotify(UUID characteristic, byte[] value) {
        // This method is for GATT-based communication.
        // Wii Balance Board uses L2CAP, so data will be handled differently.
        // This method will remain empty.
    }

    @Override
    public void disconnect() {
        Timber.d("Disconnecting from Wii Balance Board");

        if (inputThread != null) inputThread.cancel();
        if (outputThread != null) outputThread.cancel();
        if (kickStatusThread != null) kickStatusThread.cancel();
        if (blinkThread != null) blinkThread.cancel();

        try {
            if (inputThread != null) inputThread.join(100);
            if (outputThread != null) outputThread.join(100);
            if (kickStatusThread != null) kickStatusThread.join(100);
            if (blinkThread != null) blinkThread.join(100);
        } catch (InterruptedException ex) {
            Timber.e(ex, "Interrupted while joining threads");
        } finally {
            inputThread = null;
            outputThread = null;
            kickStatusThread = null;
            blinkThread = null;
        }

        super.disconnect();
    }


    @Override
    protected void onBluetoothDiscovery(BluetoothPeripheral peripheral) {
        // This method is called when a peripheral is discovered.
        // For Wii Balance Board, we initiate L2CAP connection here.
        Timber.d("Discovered peripheral: %s", peripheral.getName());
        // The actual connection will be handled by the connect(String macAddress) in superclass
        // and then onConnectedPeripheral will be called.
    }

    // Removed @Override as onConnectedPeripheral is not a supertype method in BluetoothCommunication
    public void onConnectedPeripheral(BluetoothPeripheral peripheral) {
        // super.onConnectedPeripheral(peripheral); // Removed as it's not a supertype method
        Timber.d("Connected to Wii Balance Board: %s", peripheral.getAddress());
        // L2CAP communication threads will be started in onNextStep
    }

    private void startL2CAPThreads() {
        Timber.d("Starting L2CAP communication threads");
        inputThread = new InputThread(this);
        outputThread = new OutputThread(this);

        inputThread.start();
        outputThread.start();
    }

    @Override
    protected boolean onNextStep(int stepNr) {
        if (stepNr == 0) {
            setBluetoothStatus(BT_STATUS.INIT_PROCESS);
            startL2CAPThreads(); // Start L2CAP threads after initial connection
            return true;
        }
        return false;
    }

    /**
     * Save a measurement from the scale to openScale.
     *
     * @param weightKg   The weight, in kilograms
     */
    private void saveMeasurement(float weightKg) {
        final ScaleUser scaleUser = OpenScale.getInstance().getSelectedScaleUser();
        Timber.d("Saving measurement for scale user %s", scaleUser);

        final ScaleMeasurement btScaleMeasurement = new ScaleMeasurement();
        btScaleMeasurement.setWeight(weightKg);
        btScaleMeasurement.setDateTime(new Date()); // Assign current time as Wii Balance Board doesn't provide it

        addScaleMeasurement(btScaleMeasurement);
    }

    // Inner class to handle Wii Balance Board data parsing and calibration
    public class Data {
        private int rawTl, rawTr, rawBl, rawBr;
        private int cTl[], cTr[], cBl[], cBr[];

        private static final int nSmoothItems = 20;
        private float tl[], tr[], bl[], br[];
        private int smoothIdx = 0;
        private int smoothCount = 0;
        private float stl, str, sbl, sbr;
        private float calTl, calTr, calBl, calBr;

        private boolean calibrating = false;

        private Data() {
            cTl = new int[3];
            Arrays.fill(cTl, 0);
            cTr = new int[3];
            Arrays.fill(cTr, 0);
            cBl = new int[3];
            Arrays.fill(cBl, 0);
            cBr = new int[3];
            Arrays.fill(cBr, 0);

            tl = new float[nSmoothItems];
            Arrays.fill(tl, 0);
            tr = new float[nSmoothItems];
            Arrays.fill(tr, 0);
            bl = new float[nSmoothItems];
            Arrays.fill(bl, 0);
            br = new float[nSmoothItems];
            Arrays.fill(br, 0);

            rawTl = rawTr = rawBl = rawBr = 0;
            stl = str = sbl = sbr = 0.0f;
        }

        public void setCalibrating(boolean cal) {
            if (calibrating == cal)
                return;

            calibrating = cal;

            if (calibrating) {
                calTl = calTr = calBl = calBr = 0.0f;
            } else {
                calTl = stl;
                calTr = str;
                calBl = sbl;
                calBr = sbr;
                Timber.d("Calibration complete {" + calTl + "," + calTr + "," + calBl + "," + calBr + "}");
            }
        }

        private float interpolate(int raw, int[] cal) {
            if (raw < cal[1]) {
                return 17.0f * ((float)(raw - cal[0]) / (cal[1] - cal[0]));
            }
            return 17.0f + (17.0f * ((float)(raw - cal[1]) / (cal[2] - cal[1])));
        }

        public void setRaw(int rtl, int rtr, int rbl, int rbr) {
            rawTl = Math.max(rtl, 0);
            rawTr = Math.max(rtr, 0);
            rawBl = Math.max(rbl, 0);
            rawBr = Math.max(rbr, 0);

            tl[smoothIdx] = interpolate(rawTl, cTl);
            tr[smoothIdx] = interpolate(rawTr, cTr);
            bl[smoothIdx] = interpolate(rawBl, cBl);
            br[smoothIdx] = interpolate(rawBr, cBr);

            if (smoothCount < nSmoothItems)
                smoothCount++;

            stl = arrayMean(tl, smoothIdx, smoothCount);
            str = arrayMean(tr, smoothIdx, smoothCount);
            sbl = arrayMean(bl, smoothIdx, smoothCount);
            sbr = arrayMean(br, smoothIdx, smoothCount);

            smoothIdx = (smoothIdx + 1) % nSmoothItems;
        }

        public float getTopLeft() {
            return stl - calTl;
        }

        public float getTopRight() {
            return str - calTr;
        }

        public float getBottomLeft() {
            return sbl - calBl;
        }

        public float getBottomRight() {
            return sbr - calBr;
        }

        public float getTotalWeight() {
            return getTopLeft() + getTopRight() + getBottomLeft() + getBottomRight();
        }
    }

    // Helper methods from BalanceBoard.java
    static float arrayMean(float[] a, int last, int count) {
        float total = 0.0f;
        for (int i = 0; i < count; i++) {
            int idx = last - i;
            while (idx < 0)
                idx += count;
            total += a[idx];
        }
        return total / count;
    }

    // Method to send commands to the Wii Balance Board
    private void sendCmd(byte cmd, byte[] data) {
        byte[] payload = new byte[data.length + 2];
        payload[0] = 0x52; // WM_SET_REPORT | WM_BT_OUTPUT
        payload[1] = cmd;
        System.arraycopy(data, 0, payload, 2, data.length);
        if (outputThread != null) {
            outputThread.write(payload);
        }
    }

    // Method to read data from the Wii Balance Board
    private void readData(int addr, short len, ReadListener listener) {
        ReadRequest req = new ReadRequest();
        req.addr = addr;
        req.len = len;
        req.remaining = len;
        req.data = new byte[len];
        req.listener = listener;
        enqueueReadRequest(req);
    }

    private void enqueueReadRequest(ReadRequest req) {
        synchronized (readRequests) {
            readRequests.add(req);

            if (readRequests.size() == 1)
                sendReadRequest(req);
        }
    }

    private void sendReadRequest(ReadRequest req) {
        byte[] payload = new byte[6];
        payload[0] = (byte)(req.addr >> 24);
        payload[1] = (byte)(req.addr >> 16);
        payload[2] = (byte)(req.addr >> 8);
        payload[3] = (byte)(req.addr >> 0);
        payload[4] = (byte)(req.len >> 8);
        payload[5] = (byte)(req.len >> 0);
        sendCmd(CMD_READ_DATA, payload);
    }

    private void handshakeExpansion() {
        Timber.d("Handshaking expansion");

        byte[] payload = new byte[1];
        payload[0] = 0x00;
        writeData(EXP_MEM_ENABLE, payload);

        readData(EXP_MEM_CALIBR, EXP_HANDSHAKE_LEN, new ReadListener() {
            @Override
            public void onReadDone(byte[] data) {
                Timber.d("Got expansion calibration data");

                int id = (((int)data[220] & 0xff) << 24) | (((int)data[221] & 0xff) << 16)
                        | (((int)data[222] & 0xff) << 8) | (((int)data[223] & 0xff) << 0);
                Timber.d("expansion " + Integer.toHexString(id) + " detected");

                if (id != EXP_ID_WIIBOARD) {
                    Timber.e("Invalid expansion " + Integer.toHexString(id));
                    setBluetoothStatus(BT_STATUS.UNEXPECTED_ERROR, "Invalid Wii Balance Board expansion detected.");
                    return;
                }

                ByteBuffer bbuf = ByteBuffer.wrap(data);
                bbuf.order(ByteOrder.BIG_ENDIAN);

                dat.cTr[0] = bbuf.getShort(4);
                dat.cBr[0] = bbuf.getShort(6);
                dat.cTl[0] = bbuf.getShort(8);
                dat.cBl[0] = bbuf.getShort(10);

                dat.cTr[1] = bbuf.getShort(12);
                dat.cBr[1] = bbuf.getShort(14);
                dat.cTl[1] = bbuf.getShort(16);
                dat.cBl[1] = bbuf.getShort(18);

                dat.cTr[2] = bbuf.getShort(20);
                dat.cBr[2] = bbuf.getShort(22);
                dat.cTl[2] = bbuf.getShort(24);
                dat.cBl[2] = bbuf.getShort(26);

                haveExpansion = true;
                // After handshake, proceed with connection setup
                setReportType();
            }

            @Override
            public void onReadError() {
                Timber.e("Failed to read expansion calibration");
                setBluetoothStatus(BT_STATUS.UNEXPECTED_ERROR, "Failed to read Wii Balance Board calibration data.");
            }
        });
    }

    private void writeData(int addr, byte[] data) {
        byte[] payload = new byte[21];
        payload[0] = (byte)(addr >> 24);
        payload[1] = (byte)(addr >> 16);
        payload[2] = (byte)(addr >> 8);
        payload[3] = (byte)(addr >> 0);
        payload[4] = (byte)data.length;
        System.arraycopy(data, 0, payload, 5, data.length);
        sendCmd(CMD_WRITE_DATA, payload);
    }

    private void setReportType() {
        byte[] payload = new byte[2];
        payload[0] = 0x00;
        payload[1] = 0x34; // BTN_EXP
        sendCmd(CMD_REPORT_TYPE, payload);
    }

    // L2CAP communication threads
    private class InputThread extends Thread {
        private BluetoothWiiBalanceBoard wm;
        private BluetoothSocket sk;
        private boolean canceled = false;
        private boolean ready = false;
        private java.io.InputStream in = null;

        public InputThread(BluetoothWiiBalanceBoard wm) {
            this.wm = wm;
        }

        public void cancel() {
            canceled = true;
            try {
                if (in != null)
                    in.close();
            } catch (Exception ex) {
                Timber.e(ex, "Failed to close input stream during cancel");
            }
            interrupt();
        }

        @Override
        public void run() {
            try {
                sk = WiimoteSocket.create(wm.getBluetoothDevice(), L2CAP_PSM_INPUT);
            } catch (Exception ex) {
                Timber.e(ex, "Failed to create WiimoteSocket for input");
                wm.setBluetoothStatus(BT_STATUS.UNEXPECTED_ERROR, "Failed to create input socket.");
                return;
            }

            byte[] buf = new byte[512];
            try {
                sk.connect();
                in = sk.getInputStream();
                ready = true;
                synchronized (wm.lock) { // Synchronize access to connectionReady
                    wm.connectionReady();
                }

                while (!canceled) {
                    int len = in.read(buf);
                    if (len <= 0)
                        break;
                    synchronized (wm.lock) { // Synchronize access to receivedData
                        wm.receivedData(buf, len);
                    }
                }

                ready = false;
            } catch (Exception ex) {
                if (!canceled)
                    Timber.e(ex, "Input error", ex);
            } finally {
                if (in != null) {
                    try {
                        in.close();
                    } catch (Exception ex) {
                        Timber.e(ex, "Failed to close input stream", ex);
                    }
                }
            }

            ready = false;
            Timber.d("Input thread ending");

            if (sk != null) {
                try {
                    sk.close();
                } catch (Exception ex) {
                    Timber.e(ex, "Failed to close BluetoothSocket", ex);
                }
            }

            if (!canceled) {
                wm.setBluetoothStatus(BT_STATUS.UNEXPECTED_ERROR, "Wii Balance Board input connection lost.");
            }
        }
    }

    private class OutputThread extends Thread {
        private BluetoothWiiBalanceBoard wm;
        private BluetoothSocket sk;
        private boolean canceled = false;
        private ArrayList<byte[]> queue = new ArrayList<>();
        private boolean ready = false;
        private java.io.OutputStream out = null;

        public OutputThread(BluetoothWiiBalanceBoard wm) {
            this.wm = wm;
        }

        public void cancel() {
            canceled = true;
            interrupt();

            synchronized (queue) {
                queue.notifyAll();
            }
        }

        public void write(byte[] data) {
            synchronized (queue) {
                queue.add(data);
                queue.notifyAll();
            }
        }

        @Override
        public void run() {
            try {
                sk = WiimoteSocket.create(wm.getBluetoothDevice(), L2CAP_PSM_OUTPUT);
            } catch (Exception ex) {
                Timber.e(ex, "Failed to create WiimoteSocket for output");
                wm.setBluetoothStatus(BT_STATUS.UNEXPECTED_ERROR, "Failed to create output socket.");
                return;
            }

            try {
                sk.connect();
                out = sk.getOutputStream();
                ready = true;
                synchronized (wm.lock) { // Synchronize access to connectionReady
                    wm.connectionReady();
                }

                while (!canceled) {
                    byte[] dataToSend = null;
                    synchronized (queue) {
                        while (queue.isEmpty() && !canceled) {
                            try {
                                queue.wait();
                            } catch (InterruptedException ex) {
                                // Thread interrupted, check canceled flag
                            }
                        }
                        if (canceled) break;
                        dataToSend = queue.remove(0);
                    }
                    if (dataToSend != null) {
                        out.write(dataToSend);
                    }
                }

                ready = false;
            } catch (Exception ex) {
                if (!canceled)
                    Timber.e(ex, "Output error", ex);
            } finally {
                if (out != null) {
                    try {
                        out.close();
                    } catch (Exception ex) {
                        Timber.e(ex, "Failed to close output stream", ex);
                    }
                }
            }

            ready = false;
            Timber.d("Output thread ending");

            if (sk != null) {
                try {
                    sk.close();
                } catch (Exception ex) {
                    Timber.e(ex, "Failed to close BluetoothSocket", ex);
                }
            }

            if (!canceled) {
                wm.setBluetoothStatus(BT_STATUS.UNEXPECTED_ERROR, "Wii Balance Board output connection lost.");
            }
        }
    }

    private static class KickStatusThread extends Thread {
        private BluetoothWiiBalanceBoard wm;
        private boolean canceled = false;

        public KickStatusThread(BluetoothWiiBalanceBoard wm) {
            this.wm = wm;
        }

        public void cancel() {
            canceled = true;
            interrupt();
        }

        public void run() {
            int rem = 20;
            while (!canceled && !wm.receivedStatus && rem > 0) {
                try {
                    Thread.sleep(100);
                } catch (InterruptedException ex) {
                    Timber.e(ex, "KickStatusThread interrupted");
                }
                rem--;
            }

            if (canceled)
                return;

            if (!wm.receivedStatus) {
                Timber.d("Requesting status");
                byte[] payload = new byte[1];
                payload[0] = 0;
                wm.sendCmd(CMD_CTRL_STATUS, payload);
            }

            rem = 10;
            while (!canceled && !wm.haveExpansion && rem > 0) {
                try {
                    Thread.sleep(100);
                } catch (InterruptedException ex) {
                    Timber.e(ex, "KickStatusThread interrupted");
                }
                rem--;
            }

            if (canceled)
                return;

            if (!wm.haveExpansion) {
                Timber.d("No expansion detected, abandoning board");
                wm.setBluetoothStatus(BT_STATUS.UNEXPECTED_ERROR, "No Wii Balance Board expansion detected.");
            }

            wm.kickStatusThread = null;
        }
    }

    private static class BlinkThread extends Thread {
        private BluetoothWiiBalanceBoard wm;
        private boolean canceled = false;
        private byte ledState = 0x0; // Assuming initial LED state is off

        public BlinkThread(BluetoothWiiBalanceBoard wm) {
            this.wm = wm;
        }

        public void cancel() {
            canceled = true;
            interrupt();
        }

        public void run() {
            while (!canceled) {
                // Toggle LED state (simplified, as actual LED control is in sendCmd)
                ledState = (byte) (ledState == 0x0 ? 0x1 : 0x0);
                byte[] data = new byte[1];
                data[0] = (byte)(ledState << 4);
                wm.sendCmd(CMD_LED, data);

                try {
                    Thread.sleep(500);
                } catch (InterruptedException ex) {
                    Timber.e(ex, "BlinkThread interrupted");
                }
            }
        }
    }

    private static final class ReadRequest {
        public int addr;
        public short len;
        public short remaining;
        public byte[] data;
        public ReadListener listener;
    }

    private interface ReadListener {
        void onReadDone(byte[] data);
        void onReadError();
    }

    // Helper method to get the BluetoothDevice from the superclass
    private BluetoothDevice getBluetoothDevice() {
        // btPeripheral is available from the superclass
        return BluetoothAdapter.getDefaultAdapter().getRemoteDevice(btPeripheral.getAddress());
    }

    // Method to handle received data from the InputThread
    private void receivedData(byte[] data, int len) {
        if (len < 2)
            return;

        byte event = data[1];

        switch (event) {
            case RPT_CTRL_STATUS:
                Timber.d("Control status report " + len + " bytes");
                synchronized (lock) { // Synchronize access to shared state
                    receivedStatus = true;
                    boolean expansion = (data[4] & 0x02) != 0;
                    if (expansion && !haveExpansion)
                        handshakeExpansion();
                    else if (!expansion && haveExpansion)
                        disableExpansion();
                }
                break;

            case RPT_READ:
                ReadRequest req;
                synchronized (readRequests) {
                    if (readRequests.size() == 0) {
                        Timber.e("Unexpected read report");
                        break;
                    }
                    req = readRequests.get(0);
                }
                boolean err = (data[4] & 0x0f) != 0;
                if (err) {
                    if (req.listener != null)
                        req.listener.onReadError();
                } else {
                    int rdlen = ((((int)data[4] & 0xff) & 0xf0) >> 4) + 1;
                    int off = (((int)data[5] & 0xff) << 8) + ((int)data[6] & 0xff);
                    System.arraycopy(data, 7, req.data, off - (req.addr & 0xffff), rdlen);
                    req.remaining -= rdlen;
                    if (req.remaining >= req.len)
                        req.remaining = 0;
                    if (req.remaining > 0)
                        break;
                    if (req.listener != null)
                        req.listener.onReadDone(req.data);
                }
                synchronized (readRequests) {
                    readRequests.remove(0);
                    if (readRequests.size() != 0)
                        sendReadRequest(readRequests.get(0));
                }
                break;

            case RPT_WRITE:
                Timber.d("Write report received");
                break;

            case RPT_BTN:
                Timber.d("Button report received");
                break;

            case RPT_BTN_EXP:
                Timber.d("Data report " + data.length + " bytes");
                if (len < 12)
                    break;
                ByteBuffer bbuf = ByteBuffer.wrap(data);
                bbuf.order(ByteOrder.BIG_ENDIAN);
                int rtr = bbuf.getShort(4);
                int rbr = bbuf.getShort(6);
                int rtl = bbuf.getShort(8);
                int rbl = bbuf.getShort(10);
                dat.setRaw(rtl, rtr, rbl, rbr);

                float totalWeight = dat.getTotalWeight();
                if (totalWeight > 0) { // Only save if there's actual weight
                    saveMeasurement(totalWeight);
                }

                break;

            default:
                Timber.d("Unhandled event " + event);
        }
    }

    private void connectionReady() {
        // This method will be called when both input and output threads are ready.
        // We need to ensure both are ready before proceeding.
        synchronized (lock) { // Synchronize access to shared state
            if (inputThread != null && inputThread.ready && outputThread != null && outputThread.ready) {
                Timber.d("Connection to Wii Balance Board ready");
                // Start the sequence of commands to initialize the board
                kickStatusThread = new KickStatusThread(this);
                kickStatusThread.start();
            }
        }
    }

    private void disableExpansion() {
        haveExpansion = false;
        Timber.d("Expansion disabled");
    }

    // WiimoteSocket class (updated to use new L2CAP APIs)
    private static class WiimoteSocket {
        public static android.bluetooth.BluetoothSocket create(BluetoothDevice dev, int port) {
            try {
                // Using createInsecureL2capChannel as Wii Balance Board typically uses insecure L2CAP
                return dev.createInsecureL2capChannel(port);
            } catch (Exception ex) {
                Timber.e(ex, "Failed to create WiimoteSocket using createInsecureL2capChannel");
                return null;
            }
        }
    }
}
