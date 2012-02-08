package name.pilgr.android.picat.server;

import com.esotericsoftware.kryonet.Client;
import com.esotericsoftware.kryonet.Connection;
import com.esotericsoftware.kryonet.Listener;
import name.pilgr.android.picat.shared.*;

import java.io.IOException;
import java.net.InetAddress;

import static name.pilgr.android.picat.shared.KeyEvent.VK_TAB;
import static name.pilgr.android.picat.shared.KeyEvent.VK_WINDOWS;

public class TestClient {
    static Client client;

    public static void main(String[] args) throws IOException, InterruptedException {
        client = new Client();
        client.start();
        Network.register(client);

        for (int i = 0; i < 100; i++) {
            InetAddress address = client.discoverHost(Network.UDP_PORT, 5000);
            if (address == null) {
                System.out.println("Can't discover any server");
                return;
            }
            System.out.println("Discovered address:" + address);


            client.addListener(new Listener() {
                public void received(Connection connection, Object object) {
                    if (object instanceof ServerGreeting) {
                        ServerGreeting response = (ServerGreeting) object;
                        System.out.println(response.toString());
                    } else if (object instanceof Network.SomeResponse) {
                        Network.SomeResponse response = (Network.SomeResponse) object;
                        System.out.println(response.text);
                    } else if (object instanceof ServerWindow) {
                        System.out.println("Server window changed to " + ((ServerWindow) object).getTitle());
                    }

                }
            });

            client.connect(5000, address, Network.TCP_PORT, Network.UDP_PORT);
            Thread.sleep(100);
            client.close();
        }

        /*new Thread(){
            public void run(){
                try {
                    Thread.sleep(60000);
                } catch (InterruptedException e) {
                    e.printStackTrace();
                }
                client.stop();
            }
        }.start();*/

        testMouseEvent();

    }

    private static void testMouseEvent() {
        EventSequence request = new EventSequence().
                wheel(100);

        client.sendTCP(request);
    }

    private static void testAltTab() {
        EventSequence request = new EventSequence().
                press(VK_WINDOWS).
                press(VK_TAB).delay(100).
                release(VK_TAB).delay(300).
                press(VK_TAB).delay(300).
                release(VK_TAB).delay(100).
                release(VK_WINDOWS);
        ;

        client.sendTCP(request);
    }
}
