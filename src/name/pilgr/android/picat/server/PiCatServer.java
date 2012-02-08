package name.pilgr.android.picat.server;

import com.esotericsoftware.kryonet.Connection;
import com.esotericsoftware.kryonet.Listener;
import com.esotericsoftware.kryonet.Server;
import com.jezhumble.javasysmon.JavaSysMon;
import com.jezhumble.javasysmon.ProcessInfo;
import com.sun.jna.Native;
import com.sun.jna.PointerType;
import com.sun.jna.platform.win32.WinDef;
import com.sun.jna.ptr.IntByReference;
import com.sun.jna.win32.StdCallLibrary;
import name.pilgr.android.picat.shared.*;
import name.pilgr.android.picat.shared.Event;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import uk.org.lidalia.sysoutslf4j.context.SysOutOverSLF4J;

import javax.swing.*;
import java.awt.*;
import java.awt.event.ActionEvent;
import java.awt.event.ActionListener;
import java.awt.event.ItemEvent;
import java.awt.event.ItemListener;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.IOException;
import java.net.URL;
import java.util.*;
import java.util.List;

public class PiCatServer {
    private static final String propertiesFileName = "piserver.properties";
    private static final String APP_NAME = "piServer";
    private final Logger l = LoggerFactory.getLogger(PiCatServer.class);
    private Properties prop = new Properties();
    private static final String PROP_KEY_USB_SUPPORT = "usb.support";
    private static final String PROP_KEY_REMEMBERED_DEVICES = "remembered.devices";

    private Server tcpServer, udpServer;
    private HandshakeListener handshakeListener = new HandshakeListener();
    private Robot robot;
    private List<Connection> authorizedConnections = new Vector<Connection>();
    private TrustedInterchangeListener trustedInterchangeListener = new TrustedInterchangeListener();
    private int prevHash = 0;
    private TrayIcon trayIcon;
    private boolean stopping = false;


    //User32 lib specific data
    private User32 user32 = (User32) Native.loadLibrary("user32", User32.class);
    private byte[] windowText = new byte[512];
    private static final long DELAY_OF_REQUEST_NEW_WINDOW_TITLE = 500;
    private PointerType hwnd;

    /**
     * VERSION - DATE DESCRIPTION
     * 1 - 10.07.11 Added version control of piServer
     * 2 -
     */
    private static final int APP_VERSION = 90911;

    private static final String MENU_LABEL_IS_USB_ENABLED = "USB";
    private static final String MENU_LABEL_STATE = "State...";
    private static final String MENU_LABEL_EXIT = "Exit";

    private String tempPin;
    private List<String> rememberedDevices = new Vector<String>();

    USBManager usbManager = new USBManager();
    private static final String MENU_LABEL_ABOUT = "About";

    public interface User32 extends StdCallLibrary {
        int GetWindowTextA(PointerType hWnd, byte[] lpString, int nMaxCount);

        WinDef.HWND GetForegroundWindow();

        int GetWindowThreadProcessId(PointerType hWnd, IntByReference p);
    }

    public PiCatServer() {
        //Override System.out to the logger
        SysOutOverSLF4J.sendSystemOutAndErrToSLF4J();
        l.info("Starting " + APP_NAME + " " + APP_VERSION);
        loadProperties();

        initTrayIcon();

        try {
            robot = new Robot();
        } catch (AWTException e) {
            e.printStackTrace();
            displayErrorMessage("piServer init problem", e.getMessage());
            onStop();
        }

        tcpServer = new Server();
        udpServer = new Server();
        Network.register(tcpServer);

        try {
            registerListeners();
            establishConnection();
        } catch (IOException e) {
            e.printStackTrace();
            displayErrorMessage("piServer init problem", e.getMessage());
            onStop();
        }
        startWindowChangeNotification();
    }

    private void loadProperties() {
        try {
            prop.load(new FileInputStream(propertiesFileName));
        } catch (IOException e) {
            e.printStackTrace();
        }
        boolean isUsbEnabled = Boolean.parseBoolean(prop.getProperty(PROP_KEY_USB_SUPPORT, "true"));
        usbManager.init(isUsbEnabled);

        l.debug("load remembered devices");
        String rd = prop.getProperty(PROP_KEY_REMEMBERED_DEVICES, "");
        StringTokenizer st = new StringTokenizer(rd, ";");
        while (st.hasMoreTokens()) {
            String device = st.nextToken();
            l.debug("add " + device + " to the list of remembered devices");
            rememberedDevices.add(device);
        }

    }

    private void establishConnection() throws IOException {
        udpServer.start();
        udpServer.bind(Network.TCP_PORT + 1, Network.UDP_PORT);
        tcpServer.start();
        tcpServer.bind(Network.TCP_PORT);
        usbManager.activate();
    }

    private void registerListeners() {
        tcpServer.addListener(handshakeListener);
    }

    private void onStop() {
        stopping = true;
        tcpServer.stop();
        udpServer.stop();
        saveProperties();
        usbManager.finish();
        System.exit(0);
    }

    private void saveProperties() {
        prop.setProperty(PROP_KEY_USB_SUPPORT, Boolean.toString(usbManager.isUsbEnabled()));

        //Save remembered devices
        StringBuilder sb = new StringBuilder();
        for (String d : rememberedDevices) {
            sb.append(d).append(";");
        }
        prop.setProperty(PROP_KEY_REMEMBERED_DEVICES, sb.toString());

        try {
            prop.store(new FileOutputStream(propertiesFileName), "This is a piServer properties file");
        } catch (IOException e) {
            e.printStackTrace();
        }

    }

    private void initTrayIcon() {
        if (SystemTray.isSupported()) {

            SystemTray tray = SystemTray.getSystemTray();

            ActionListener exitListener = new ActionListener() {
                public void actionPerformed(ActionEvent e) {
                    onStop();
                }
            };

            PopupMenu popup = new PopupMenu();

            //USB flag
            final CheckboxMenuItem usbEnabledItem = new CheckboxMenuItem(MENU_LABEL_IS_USB_ENABLED);
            usbEnabledItem.setState(usbManager.isUsbEnabled());
            popup.add(usbEnabledItem);
            usbEnabledItem.addItemListener(new ItemListener() {
                @Override
                public void itemStateChanged(ItemEvent e) {
                    if (usbEnabledItem.getState()) {
                        l.debug("Enable USB from tray menu");
                        usbManager.enableUSBSupport();
                    } else {
                        l.debug("disable USB from tray menu");
                        usbManager.disableUSBSupport();
                    }
                }
            }
            );
            //Stats
            MenuItem state = new MenuItem(MENU_LABEL_STATE);
            popup.add(state);
            state.addActionListener(new ActionListener() {
                @Override
                public void actionPerformed(ActionEvent e) {
                    StringBuilder sb = new StringBuilder();
                    if (authorizedConnections.size() == 0) {
                        sb.append("You don't have any active connections");
                    } else {
                        sb.append("List of active connections:\n");
                    }
                    for (Connection con : authorizedConnections) {
                        if (con.getRemoteAddressTCP().toString().contains("127.0.0.1")) {
                            sb.append("USB: ");
                        } else {
                            sb.append("Wi-Fi: ");
                        }
                        sb.append(con.getRemoteAddressTCP()).append("\n");
                    }
                    JOptionPane.showMessageDialog(new Frame(), sb.toString(), "State", JOptionPane.PLAIN_MESSAGE);
                }
            });
            //Divider
            popup.add(new MenuItem("-"));
            //Stats
            MenuItem about = new MenuItem(MENU_LABEL_ABOUT + "...");
            popup.add(about);
            about.addActionListener(new ActionListener() {
                @Override
                public void actionPerformed(ActionEvent e) {
                    StringBuilder sb = new StringBuilder();
                    sb.append(APP_NAME).append(" provides control for your PC by android devices with the piCat application").append("\n");
                    sb.append("version ").append(APP_VERSION).append("\n");
                    sb.append("Created by Aleksey Masny").append("\n");
                    sb.append("@pilgr | pilgr.name | aleksey.masny@gmail.com");
                    JOptionPane.showMessageDialog(new Frame(), sb.toString(), MENU_LABEL_ABOUT, JOptionPane.PLAIN_MESSAGE);
                }
            });
            //Divider
            popup.add(new MenuItem("-"));
            //Exit
            MenuItem exitItem = new MenuItem(MENU_LABEL_EXIT);
            exitItem.addActionListener(exitListener);
            popup.add(exitItem);

            trayIcon = new TrayIcon(createImage("/img/tray_icon_50.png", "tray icon"), APP_NAME, popup);
            trayIcon.setImageAutoSize(true);

            try {
                tray.add(trayIcon);
            } catch (AWTException e) {
                e.printStackTrace();
            }

        } else {
            l.error("System tray is currently not supported.");
        }
    }

    private void displayErrorMessage(String title, String message) {
        displayMessage(title, message, TrayIcon.MessageType.ERROR);
    }

    private void displayInfoMessage(String title, String message) {
        displayMessage(title, message, TrayIcon.MessageType.INFO);
    }

    private void displayMessage(String title, String message, TrayIcon.MessageType type) {
        if (trayIcon != null) {
            trayIcon.displayMessage(title,
                    message,
                    type);
        }
    }

    private void startWindowChangeNotification() {
        Thread notificationThread = new Thread() {
            public void run() {

                while (!stopping) {
                    windowChangeNotification();
                    try {
                        Thread.sleep(DELAY_OF_REQUEST_NEW_WINDOW_TITLE);
                    } catch (InterruptedException e) {
                        e.printStackTrace();
                    }
                }
            }
        };
        notificationThread.start();
    }

    private void windowChangeNotification() {
        ServerWindow msg = getCurrentServerWindow();
        int currHash = (msg.getTitle() + msg.getProcessName()).hashCode();
        if (currHash != prevHash) {
            prevHash = currHash;

            for (Connection con : authorizedConnections) {
                con.sendTCP(msg);
            }
        }
    }

    private ServerWindow getCurrentServerWindow() {

        hwnd = user32.GetForegroundWindow(); // assign the window handle here.
        user32.GetWindowTextA(hwnd, windowText, 512);
        String title = Native.toString(windowText);

        IntByReference p = new IntByReference();
        user32.GetWindowThreadProcessId(hwnd, p);
        int pid = p.getValue();
        String processName = getProcessNameByPid(pid);

        //l.debug("titile:" + title + " pid:" + pid + "procname:" + processName);
        ServerWindow msg = new ServerWindow(title, processName);

        return msg;
    }

    private String getProcessNameByPid(int pid) {
        ProcessInfo[] pidTable = new JavaSysMon().processTable();
        for (ProcessInfo info : pidTable) {
            if (info.getPid() == pid) {
                return info.getName();
            }
        }
        return "";
    }


    private void runEventSequence(EventSequence sequence) {
        for (Event event : sequence.getSequence()) {

            //KEY event
            if (event instanceof KeyEvent) {
                KeyEvent keyEvent = (KeyEvent) event;
                if (keyEvent.press) {
                    robot.keyPress(keyEvent.code);
                } else {
                    robot.keyRelease(keyEvent.code);
                }
            }
            //DELAY event
            else if (event instanceof DelayEvent) {
                DelayEvent delayEvent = (DelayEvent) event;
                try {
                    Thread.sleep(delayEvent.getDelayTime());
                } catch (InterruptedException e) {
                    e.printStackTrace();
                }
            }
            //MOUSE event
            else if (event instanceof MouseEvent) {
                MouseEvent e = (MouseEvent) event;

                if (e.getEventType() == MouseEvent.Type.WHEEL) {
                    robot.mouseWheel(e.getWheelAmt());

                } else if (e.getEventType() == MouseEvent.Type.BUTTON) {
                    if (e.isPress()) {
                        robot.mousePress(e.getButton());
                    } else {
                        robot.mouseRelease(e.getButton());
                    }
                }
            }

        }

    }

    private void executeCommand(ExecutableCommand command) {
        //EXECUTABLE COMMAND event
        try {
            Runtime.getRuntime().exec(command.getCommand());
        } catch (IOException e1) {
            e1.printStackTrace();
        }
    }

    //Obtain the image URL
    protected Image createImage(String path, String description) {
        URL imageURL = PiCatServer.class.getResource(path);

        if (imageURL == null) {
            l.error("Resource not found: " + path);
            return null;
        } else {
            return (new ImageIcon(imageURL, description)).getImage();
        }
    }

    public class HandshakeListener extends Listener {
        private String tempUserAccountName;
        private String tempUserAccountType;

        @Override
        public void received(Connection connection, Object object) {
            if (object instanceof Handshake) {
                Handshake h = (Handshake) object;
                switch (h.getId()) {
                    case Handshake.PHONE_TRUST_ME:
                        l.debug("Receive PHONE:TRUST_ME");
                        tempUserAccountName = h.getName();
                        tempUserAccountType = h.getType();
                        if (isAlreadyRememberedDevice()) {
                            finishAuthorization(connection);
                        } else {
                            displayInfoMessage("", "Please enter PIN " + regeneratePin() + " on the piCat client (" + tempUserAccountName + ")");
                            connection.sendTCP(new Handshake().pc_EnterPin());
                        }
                        break;
                    case Handshake.PHONE_SENT_PIN:
                        l.debug("Receive PHONE:SENT_PIN");
                        String pin = h.getPin();
                        l.debug("pin = " + pin);
                        if (pin.equals(tempPin)) {
                            finishAuthorization(connection);
                            if (h.isRemember()) rememberDevice();
                        } else {
                            displayInfoMessage("", "Please enter PIN " + regeneratePin() + " on the piCat client (" + tempUserAccountName + ")");
                            connection.sendTCP(new Handshake().pc_IncorrectPin());
                        }
                        break;
                    default:
                        break;
                }
            }
        }

        private void finishAuthorization(Connection con) {
            l.debug("Sent PC:I_TRUST_PHONE");
            con.sendTCP(new Handshake().pc_ITrustPhone());
            authorizedConnections.add(con);

            ServerGreeting greeting = new ServerGreeting();
            greeting.osName = System.getProperty("os.name");
            greeting.osVersion = System.getProperty("os.version");
            greeting.userName = System.getProperty("user.name");
            greeting.approvedByUser = true;
            greeting.serverWindow = getCurrentServerWindow();
            greeting.piServerVersion = APP_VERSION;

            con.sendTCP(greeting);
            con.addListener(trustedInterchangeListener);
            if (!isAlreadyRememberedDevice()) {
                displayInfoMessage("Connection", "piCat client has been connected");
            }
        }

        private void rememberDevice() {
            rememberedDevices.add(tempUserAccountName + "+" + tempUserAccountType);
        }

        private boolean isAlreadyRememberedDevice() {
            for (String d : rememberedDevices) {
                if (d.equals(tempUserAccountName + "+" + tempUserAccountType)) return true;
            }
            return false;
        }
    }

    private String regeneratePin() {
        String time = Long.toString(System.currentTimeMillis());
        tempPin = time.substring(time.length() - 4);
        return tempPin;
    }

    private class TrustedInterchangeListener extends Listener {
        @Override
        public void received(Connection connection, Object object) {
            if (object instanceof EventSequence) {
                runEventSequence((EventSequence) object);
            } else if (object instanceof ExecutableCommand) {
                executeCommand((ExecutableCommand) object);
            }
        }

        @Override
        public void disconnected(Connection connection) {
            authorizedConnections.remove(connection);
        }
    }

    private class USBManager implements OnUsbDeviceIsReady {
        private USBOverAdbManager adb = new USBOverAdbManager();

        private boolean isUsbEnabled = false;
        private HashMap<Integer, USBDiscoveryThread> usbThreadMap = new HashMap<Integer, USBDiscoveryThread>();

        private void disableUSBSupport() {
            isUsbEnabled = false;
            shutdownAndClearAllUsbThread();
            adb.disable();
        }

        private void enableUSBSupport() {
            isUsbEnabled = true;
            adb.enable();
        }

        @Override
        public void deviceAdded(String serial, int port) {
            USBDiscoveryThread usbThread = new USBDiscoveryThread(port, handshakeListener);
            usbThreadMap.put(port, usbThread);
            usbThread.start();
        }

        @Override
        public void deviceRemoved(String serial, int port) {
            USBDiscoveryThread thread = usbThreadMap.get(port);
            if (thread != null) {
                thread.shutdown();
            }
            usbThreadMap.remove(port);
        }

        public boolean isUsbEnabled() {
            return isUsbEnabled;
        }

        public void activate() {
            if (isUsbEnabled) {
                adb.enable();
            }
        }

        public void init(boolean usbEnabled_) {
            isUsbEnabled = usbEnabled_;
            adb.init();
            adb.setListener(this);
        }

        public void finish() {
            shutdownAndClearAllUsbThread();
            adb.disable();
            adb.finish();
        }

        private void shutdownAndClearAllUsbThread() {
            for (USBDiscoveryThread t : usbThreadMap.values()) {
                if (t != null) t.shutdown();
            }
            usbThreadMap.clear();
        }
    }
}
