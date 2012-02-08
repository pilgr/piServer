package name.pilgr.android.picat.server;

import com.esotericsoftware.kryonet.Client;
import com.esotericsoftware.kryonet.Connection;
import com.esotericsoftware.kryonet.Listener;
import com.esotericsoftware.kryonet.Server;
import name.pilgr.android.picat.shared.Handshake;
import name.pilgr.android.picat.shared.Network;
import name.pilgr.android.picat.shared.ServerGreeting;
import name.pilgr.android.picat.shared.ServerWindow;

import java.io.IOException;
import java.net.InetAddress;

public class TestCatClient {

    private Server usbServer;
    private Connection usbConnection = null;
    private boolean stopping = false;
    private Client udpClient, tcpClient;
    private Connection tcpConnection = null;

    public TestCatClient() {
        usbServer = new Server();
        tcpClient = new Client();
        udpClient = new Client();
        Network.register(usbServer);
        Network.register(tcpClient);

        try {
            establishConnection();
            registerServerListeners();
        } catch (IOException e) {
            e.printStackTrace();
            System.err.println("piServer init problem" + e.getMessage());
            onStop();
        }


    }

    private void onStop() {
        usbServer.stop();
        tcpClient.stop();
        udpClient.stop();
        System.exit(0);
    }

    private void establishConnection() throws IOException {
        enableUsb();
        tcpClient.start();
        udpClient.start();
        new ConnectionThread().start();
        System.out.println("USB server started");
    }

    private void registerServerListeners() {
        usbServer.addListener(new Listener() {
            @Override
            public void connected(Connection connection) {
                usbConnection = connection;
            }

            @Override
            public void disconnected(Connection connection) {
                usbConnection = null;
                new ConnectionThread().start();
            }
        });
        tcpClient.addListener(new Listener() {
            @Override
            public void connected(Connection connection) {
                tcpConnection = connection;
            }

            @Override
            public void disconnected(Connection connection) {
                tcpConnection = null;
                if (usbConnection == null) {
                    try {
                        enableUsb();
                    } catch (IOException e) {
                        e.printStackTrace();  //To change body of catch statement use File | Settings | File Templates.
                    }
                }
                new ConnectionThread().start();
            }
        });
        usbServer.addListener(trustedListener);
        tcpClient.addListener(trustedListener);
    }

    private void disableUsb() {
        usbServer.stop();
        log("USB disabled");
    }

    private void enableUsb() throws IOException {
        usbServer.start();
        usbServer.bind(Network.USB_PORT);
        log("USB enabled");
    }

    private class ConnectionThread extends Thread {
        @Override
        public void run() {
            while (!stopping) {
                /**
                 * WiFi
                 */
                InetAddress address = udpClient.discoverHost(Network.UDP_PORT, 5000);
                if (address == null) {
                    log("Can't discover any available server over WiFi");
                    continue;
                }
                log("Discovered server address:" + address);

                /**
                 * USB
                 */
                if (usbConnection != null) {
                    usbConnection.sendTCP(new Handshake().phone_TrustMe("dsg","dv"));
                    log("Connected over USB");
                    break;
                }

                try {

                    tcpClient.connect(5000, address, Network.TCP_PORT);
                    tcpClient.sendTCP(new Handshake().phone_TrustMe("sdf","sdf"));
                    log("Connected over WiFI");
                    break;
                } catch (IOException e) {
                    e.printStackTrace();
                }
            }
        }
    }

    private Listener trustedListener = new Listener() {
        @Override
        public void received(Connection connection, Object object) {
            if (object instanceof ServerGreeting) {
                ServerGreeting response = (ServerGreeting) object;
                System.out.println(response.toString());
            } else if (object instanceof Network.SomeResponse) {
                Network.SomeResponse response = (Network.SomeResponse) object;
                System.out.println(response.text);
            } else if (object instanceof ServerWindow) {
                System.out.println("Server window changed to " + ((ServerWindow) object).getTitle());
            } else if (object instanceof Handshake) {
                Handshake h = (Handshake) object;
                switch (h.getId()) {
                    case Handshake.PC_ENTER_PIN:
                        log("Received PC:ENTER_PIN");
                        break;
                    case Handshake.PC_INCORRECT_PIN:
                        log("Received PC:PIN_FAILED");
                        break;
                    case Handshake.PC_I_TRUST_PHONE:
                        if (usbConnection == null) {
                            disableUsb();
                        }
                        log("Received PC:I_TRUST_PHONE");
                        break;
                    default:
                        break;
                }
            }

        }
    };

    public static void main(String[] args) throws IOException, InterruptedException {
        new TestCatClient();
    }

    public static void log(String s) {
        System.out.println(s);
    }
}
