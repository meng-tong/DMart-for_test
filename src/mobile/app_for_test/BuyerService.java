package mobile.app_for_test;

import java.net.DatagramPacket;
import java.net.DatagramSocket;
import java.net.InetSocketAddress;
import java.net.SocketAddress;
import java.nio.ByteBuffer;
import java.nio.channels.DatagramChannel;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.IOException;

import android.app.PendingIntent;
import android.content.Intent;
import android.os.Handler;
import android.os.HandlerThread;
import android.os.Message;
import android.os.ParcelFileDescriptor;
import android.net.VpnService;
import android.util.Log;
import android.widget.Toast;



// TODO: heart-beat, etc.
public class BuyerService extends VpnService implements Handler.Callback {
    
    private static final String TAG             = "BuyerService";
    private static final int    PROTOCOL_TCP    = 6;
    private static final int    PROTOCOL_UDP    = 17;
    private static final int    PROTOCOL_OFFSET = 9; //9 for sure? -tmeng6

    // To be obtained via Intent, it is DMartClient's job to contact WifiP2pManager and get the address.
    private String               mServerAddress   = null;
    private int                  mServerPort      = Config.DEFAULT_PORT_NUMBER;

    private PendingIntent        mConfigureIntent;

    private HandlerThread        mThread;
    private Handler              mHandler;
    private Handler              mTunnelHandler;

    private ParcelFileDescriptor mInterface;
    // VPN interface -> reseller
    private FileInputStream      mOutTraffic      = null;
    // reseller -> VPN interface
    private FileOutputStream     mInTraffic       = null;
    private DatagramSocket       mTunnel          = null;

    private boolean              mConnected       = false;

    /* Helper Methods */
    /** Authenticate and bring up the VPN interface. */
    private void handshake() throws IOException {
        if (mInterface == null) {
            Builder builder = new Builder();

            builder.setMtu(Config.DEFAULT_MTU);
            builder.addAddress(Config.DEFAULT_VPN_CLIENT_ADDR, 24);
            builder.addRoute("0.0.0.0", 0);
            //builder.addDnsServer("8.8.8.8");
            //builder.addSearchDomain("wisc.edu");

            // Create a new interface using the builder and save the parameters.
            // without further call on mConfigureIntent? -tmeng6
            mInterface = builder.setSession(mServerAddress).setConfigureIntent(mConfigureIntent).establish();

            // TODO: send hello packet and wait for reply or timeout before updating status.
            // For timeout, throw an IOException with "connection timed out"
        }
    }

    /** Initiate the UDP Tunnel connection. */
    private void startTunnel() throws Exception {
        if (mConnected == true) {
            throw new IllegalStateException("We are already connected!");
        }

        // Use a DatagramChannel so we can get non-blocking sockets, thus we can operate TX and RX in one thread.
        DatagramChannel channel = DatagramChannel.open();
        mTunnel = channel.socket();
        SocketAddress mTunnelAddress = new InetSocketAddress(Config.DEFAULT_LOCAL_PORT);
        mTunnel.bind(mTunnelAddress);
        if (mTunnel == null) {
            throw new IllegalStateException("Datagram socket is null! Maybe too many open files in system");
        }

        // Protect the tunnel before connecting to avoid loopback.
        if (!protect(mTunnel)) {
            throw new IllegalStateException("Cannot protect the local tunnel");
        }

        // Connect to the server.
        channel.connect(new InetSocketAddress(mServerAddress, mServerPort));
        channel.configureBlocking(false);

        // Get VPN interface IO.
        mOutTraffic = new FileInputStream(mInterface.getFileDescriptor());
        mInTraffic  = new FileOutputStream(mInterface.getFileDescriptor());

        // Authenticate and configure the virtual network interface.
        handshake();

        // Now we are connected. Set the flag and show the message.
        mConnected = true;
        mHandler.sendEmptyMessage(R.string.connected);
    }

    /** Poll outgoing packets and send them to reseller via UDP Tunnel. */
    private boolean pollOutgoing() {
        boolean packetProcessed = false;

        try {
            // Allocate the buffer for a single packet.
            ByteBuffer packet = ByteBuffer.allocate(Config.DEFAULT_MTU);
            int length = 0;
            while ((length = mOutTraffic.read(packet.array())) > 0) {
                packet.limit(length); // As-is, do not know whether is necessary.

                // Drop anything that is not TCP or UDP since reseller is not going to handle it.
                int protocol = packet.get(PROTOCOL_OFFSET);
                if ((protocol != PROTOCOL_TCP) && (protocol != PROTOCOL_UDP)) {
                    Log.i(TAG, "Dropping packet of unsupported type: " + protocol + ", length: " + length);
                    continue;
                }

                // Simply enclose it in an UDP packet and send.
                try {
                    mTunnel.send(new DatagramPacket(packet.array(), length));
                } catch (Exception e) {
                    Log.e(TAG, "Send to seller failed: " + e.toString());
                }

                // Erase and reallocate
                packet.clear();
                packet = ByteBuffer.allocate(Config.DEFAULT_MTU);
                packetProcessed = true;
            }
        } catch (Exception e) {
            Log.e(TAG, "Poll outgoing failed: " + e.toString());
        }

        return packetProcessed;
    }

    /** Poll incoming packets and send them to VPN interface. */
    private boolean pollIncoming() {
        boolean packetProcessed = false;
        
        try {
        	ByteBuffer packet = ByteBuffer.allocate(Config.DEFAULT_MTU);
            int length = 0;
            //Or change to mTunnel.receive(..)? -tmeng6
            while((length = mTunnel.getChannel().read(packet)) > 0) {
            	//Not sure if this is necessary -tmeng6
            	if(packet.get(0) == 0) {
            		Log.i(TAG, "Dropping packet starting with 0");
            		continue;
            	}
            	
            	int protocol = packet.get(PROTOCOL_OFFSET);
            	if((protocol!=PROTOCOL_TCP) && (protocol!=PROTOCOL_UDP)) {
            		Log.i(TAG, "Dropping packet of unsupported type: " + protocol + ", length: " + length);
            		continue;
            	}
            	
            	try {
            		mInTraffic.write(packet.array(), 0, length);
            	} catch (Exception e) {
            		Log.e(TAG, "Receive from seller failed: " + e.toString());
            	}
            	
            	// Erase and reallocate
            	packet.clear();
            	packet = ByteBuffer.allocate(Config.DEFAULT_MTU);
            	packetProcessed = true;
            }
        } catch (Exception e) {
        	Log.e(TAG, "Poll incoming failed: " + e.toString());
        }
    
        return packetProcessed;
    }

    /* Framework Overrides */
    @Override
    public int onStartCommand(Intent intent, int flags, int startId) {
        // The handler is only used to show messages.
        if (mHandler == null) {
            // I handle callback by my own handleMessage()
            mHandler = new Handler(this);
        }

        // Stop the previous session by interrupting the thread.
        // TODO: How could this happen?
        if (mThread != null) {
            Log.i(TAG, "Stopping previous thread");
            mThread.interrupt();
        }

        // Get server address from Intent
        String prefix = getPackageName();
        mServerAddress = intent.getStringExtra(prefix+".serverADDR");
        if (mServerAddress == null) {
            throw new IllegalArgumentException("Server address not found in Intent!");
        }

        // Start a new session by creating a new thread.
        mThread = new HandlerThread("BuyerServiceThread");
        mThread.run(); // the difference from start()?(tmeng6)
        mTunnelHandler = new Handler(mThread.getLooper());
        mTunnelHandler.post(startServer);

        // START_STICKY is used for services that are explicitly started and stopped as needed
        return START_STICKY;
    }

    @Override
    public void onDestroy() {
        if (mConnected) {
            // TODO: send Good-bye message
            // or maybe this should be done by discovery module?
        }

        if (mThread != null) {
            mThread.quit();
        }
    }

    @Override
    public boolean handleMessage(Message message) {
        if (message != null) {
            Toast.makeText(this, message.what, Toast.LENGTH_SHORT).show();
        }
        return true;
    }

    // Posted by onStartCommand and never again
    Runnable startServer = new Runnable() {
        @Override
        public void run() {
            Log.i(TAG, "Starting");
            try {
                // Start the VPN
                mHandler.sendEmptyMessage(R.string.connecting);
                startTunnel();
                // Start polling
                mTunnelHandler.post(handlePackets);
            } catch (Exception e) {
                Log.e(TAG, "Got " + e.toString());
                try {
                    mInterface.close();
                } catch (Exception e2) {
                    Log.e(TAG, "Got " + e2.toString());
                }
                mConnected = false;
                mHandler.sendEmptyMessage(R.string.disconnected);
            }
        }
    };

    // Polling call
    Runnable handlePackets = new Runnable() {
        // One Thread checks both: feed packets into the UDP Tunnel and fetching them from the UDP Tunnel.
        // A reverse App-Layer NAT (or whatever it is) is NOT needed for returning packets, we can write RAW packets to the VPN interface.
        @Override
        public void run() {
        	boolean InFlag, OutFlag;
        	InFlag = pollIncoming();
        	OutFlag = pollOutgoing();
        	if(InFlag || OutFlag) {
        		mTunnelHandler.post(handlePackets);
        	} else {
        		mTunnelHandler.postDelayed(handlePackets, Config.DEFAULT_POLL_MS);
        	}
        	
        	/*
            // Receiving should have a higher priority.
            if (pollIncoming()) {
                // Something happened, expect more. Do not wait.
                mTunnelHandler.post(handlePackets);
            } else if (pollOutgoing()) {
                // Something happened, expect more. Do not wait.
                mTunnelHandler.post(handlePackets);
            } else {
                // There seems to be no event-based framework so we have to poll. Consider polling interval carefully.
                // Nothing interesting happened. Sleep for next poll.
                mTunnelHandler.postDelayed(handlePackets, BuyerConfig.DEFAULT_POLL_MS);
            }
            */ //the detailed polling scheme may be adjusted
        }
    };

}