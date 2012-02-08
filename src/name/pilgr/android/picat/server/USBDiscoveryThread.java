package name.pilgr.android.picat.server;

import com.esotericsoftware.kryonet.Client;
import com.esotericsoftware.kryonet.Connection;
import com.esotericsoftware.kryonet.Listener;
import name.pilgr.android.picat.shared.Network;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.IOException;

class USBDiscoveryThread extends Thread {
    private final Logger l = LoggerFactory.getLogger(USBDiscoveryThread.class);
    private static final long USB_DISCOVERY_INTERVAL = 1000;
    private static final int USB_CONNECTION_TIMEOUT = 2000;

    private volatile boolean shutdown = true;
    private Client _client = new Client();
    private int port;
    private final Object monitor = new Object();
    private String tag;

    public USBDiscoveryThread(int port_, PiCatServer.HandshakeListener handshakeListener_) {
        port = port_;
        tag = "["+Integer.toString(port) + "] ";
        Network.register(_client);
        _client.addListener(handshakeListener_);
        _client.addListener(new Listener() {
            @Override
            public void disconnected(Connection connection) {
                l.debug(tag + "USB connection closed. Try to search a connection again");
                breakWaiting();
            }
        });
    }

    @Override
    public void run() {
        l.debug(tag + "Starting USB discovery thread");
        shutdown = false;
        _client.start();
        while (!shutdown) {
            /**
             * USB
             */
            try {
                l.debug(tag + "Try to establish USB connection..");
                _client.connect(USB_CONNECTION_TIMEOUT, "127.0.0.1", port);
                l.debug(tag + "Sleep to wait shutdown or client disconnection");
                waitDisconnectionToContinue();
            } catch (IOException e) {
                l.debug(tag + "USB connection not established");
            }

            if (shutdown) break;

            try {
                Thread.sleep(USB_DISCOVERY_INTERVAL);
            } catch (InterruptedException e) {
                e.printStackTrace();
            }
        }
        _client.stop();
        l.debug(tag + "USB discovery thread has died");
    }

    private void waitDisconnectionToContinue() {
        while (_client.isConnected()) {
            synchronized (monitor) {
                try {
                    monitor.wait();
                } catch (InterruptedException e) {
                    e.printStackTrace();
                }
            }
        }
    }

    private void breakWaiting() {
        synchronized (monitor) {
            monitor.notifyAll();
        }
    }

    public void shutdown() {
        shutdown = true;
        _client.stop();
        this.interrupt();
        breakWaiting();
    }
}