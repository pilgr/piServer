package name.pilgr.android.picat.server;

import com.android.ddmlib.AdbCommandRejectedException;
import com.android.ddmlib.AndroidDebugBridge;
import com.android.ddmlib.IDevice;
import com.android.ddmlib.TimeoutException;
import name.pilgr.android.picat.shared.Network;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.IOException;
import java.util.HashMap;

/**
 * Created by Samsung Electronics Company Ltd. SURC
 * User: Aleksey Masny [o.masnyi]
 * Date: 31.08.11
 * Time: 8:14
 */
public class USBOverAdbManager {
    private final Logger l = LoggerFactory.getLogger(USBOverAdbManager.class);

    //For key uses port number
    HashMap<Integer, IDevice> devList = new HashMap<Integer, IDevice>();
    OnUsbDeviceIsReady listener;

    public void init() {
        AndroidDebugBridge.init(false);
        l.debug("adb initialized");
    }

    public void finish() {
        devList.clear();
        AndroidDebugBridge.terminate();
        l.debug("adb terminated");
    }

    public void enable() {
        AndroidDebugBridge.addDeviceChangeListener(new AndroidDebugBridge.IDeviceChangeListener() {
            // this gets invoked on another thread, but you probably shouldn't count on it
            public void deviceConnected(IDevice device) {
                l.debug("++ " + device.getSerialNumber() + " " + device.getState());
                //Try to set tcp forward for online device
                processDevice(device);
            }

            public void deviceDisconnected(IDevice device) {
                l.debug("-- " + device.getSerialNumber() + " " + device.getState());
                if (!devList.containsValue(device)) return;

                int localPort = releasePort(device);
                if (listener != null) {
                    listener.deviceRemoved(device.getSerialNumber(), localPort);
                }
            }

            public void deviceChanged(IDevice device, int changeMask) {
                l.debug("** " + device.getSerialNumber() + " " + device.getState());
                processDevice(device);
            }
        });

        AndroidDebugBridge adb = AndroidDebugBridge.createBridge("adb.exe", false);
        l.debug("adb enabled");
    }

    private void processDevice(IDevice device) {
        //If device already processed - exit
        if (devList.containsValue(device)) return;

        if (device.getState() == IDevice.DeviceState.ONLINE) {
            try {
                int localPort = takePort(device);
                l.debug("forward tcp:" + localPort + " tcp:54888");
                device.createForward(localPort, Network.USB_PORT);
                if (listener != null) {
                    listener.deviceAdded(device.getSerialNumber(), localPort);
                }
            } catch (TimeoutException e) {
                e.printStackTrace();
            } catch (AdbCommandRejectedException e) {
                e.printStackTrace();
            } catch (IOException e) {
                e.printStackTrace();
            }
        }
        try {
            Thread.sleep(500);
        } catch (InterruptedException e) {
            e.printStackTrace();
        }
    }

    private int releasePort(IDevice device_) {
        int foundPort = 0;
        for (Integer p : devList.keySet()) {
            IDevice d = devList.get(p);
            if (device_ == d) {
                foundPort = p;
                break;
            }
        }
        devList.remove(foundPort);
        l.debug("Release the port# " + foundPort);
        return foundPort;
    }

    private int takePort(IDevice device_) {
        int p = Network.USB_PORT;
        //If device with a same port a connected, the continue to find available port
        while (devList.containsKey(p)) {
            p++;
        }
        devList.put(p, device_);
        l.debug("Take the port# " + p);
        return p;
    }

    public void disable() {
        AndroidDebugBridge.disconnectBridge();
        devList.clear();
        l.debug("adb disabled");
    }


    void setListener(OnUsbDeviceIsReady l) {
        listener = l;
    }

}

interface OnUsbDeviceIsReady {
    public void deviceAdded(String serial, int port);

    public void deviceRemoved(String serial, int port);

}