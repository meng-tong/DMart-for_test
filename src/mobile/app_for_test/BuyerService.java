package mobile.app_for_test;

import java.net.DatagramPacket;
import java.net.DatagramSocket;
import java.net.InetSocketAddress;
import java.net.SocketAddress;
import java.nio.ByteBuffer;
import java.nio.channels.DatagramChannel;
import java.util.Random;
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
    
    private static final String buyerTAG = "BuyerService";
    
    private DatagramSocket 		 mTunnel		= null;
    private FileInputStream 	 mOutTraffic	= null; //VPN interface -> seller
    private FileOutputStream 	 mInTraffic		= null; //seller -> VPN interface
    private ParcelFileDescriptor mInterface;
    // To be obtained via Intent, it is DMartClient's job to contact WifiP2PManager and get the address.
    private String  mServerAddress 	= null;
    private int     mServerPort 	= Config.DEFAULT_PORT_NUMBER;
    
    private Handler              mHandler;
    private HandlerThread        mThread;
    private Handler              mThreadHandler;

    private PendingIntent        mConfigureIntent;

    private boolean              mConnected       = false;
    
    @Override
    public int onStartCommand(Intent intent, int flags, int startId) {
        // mHandler is only used to show toast messages.
        if (mHandler == null) {
            // I handle callback by my own handleMessage()
        	//Log.d(buyerTAG, "Buyer Initialize the mHandler");
            mHandler = new Handler(this);
        }
        // Stop the previous session by interrupting the thread.
        // TODO: use interrupt() or other method?
        if (mThread != null) {
            Log.d(buyerTAG, "Stopping previous thread");
            mConnected = false; //TODO: make sure this
            mThread.interrupt();
        }

        // Get server address from Intent
        String prefix = getPackageName();
        mServerAddress = intent.getStringExtra(prefix+".serverADDR");
        if (mServerAddress == null) {
            throw new IllegalArgumentException("Server address not found in Intent!");
        }
        //Log.d(buyerTAG, "Buyer Get Server Address: " + mServerAddress);

        // Start a new session by creating a new thread.
        mThread = new HandlerThread("BuyerServiceThread");
        mThread.start(); // the difference from start()?(tmeng6)
        mThreadHandler = new Handler(mThread.getLooper());
        mThreadHandler.post(startServer);

        // START_STICKY is used for services that are explicitly started and stopped as needed
        return START_STICKY;
    }
    
    // Posted by onStartCommand and never again
    Runnable startServer = new Runnable() {
        @Override
        public void run() {
            Log.d(buyerTAG, "Starting");
            try {
                // Start the VPN
                mHandler.sendEmptyMessage(R.string.connecting);
                startTunnel();
                // Start polling
                mThreadHandler.post(handlePackets);
            } catch (Exception e) {
                Log.e(buyerTAG, "Got " + e.toString());
                try {
                    mInterface.close();
                } catch (Exception e2) {
                    Log.e(buyerTAG, "Got " + e2.toString());
                }
                mConnected = false;
                mHandler.sendEmptyMessage(R.string.disconnected);
            }
        }
    };
    
    /** Initiate the UDP Tunnel connection. */
    private void startTunnel() throws Exception {
        if (mConnected == true) {
            throw new IllegalStateException("We are already connected!");
        }

        // Use a DatagramChannel so we can get non-blocking sockets, thus we can operate TX and RX in one thread.
        DatagramChannel channel = DatagramChannel.open();
        mTunnel = channel.socket();
        SocketAddress mTunnelAddress = new InetSocketAddress(Config.BUYER_CLIENT_PORT);
        mTunnel.bind(mTunnelAddress);
        if (mTunnel == null) {
            throw new IllegalStateException("Datagram socket is null! Maybe too many open files in system");
        }

        // Protect the tunnel before connecting to avoid loopback.
        //TODO: check whether protect is necessary
        /*if (!protect(mTunnel)) {
            throw new IllegalStateException("Cannot protect the local tunnel");
        }*/

        // Connect to the server.
        channel.connect(new InetSocketAddress(mServerAddress, mServerPort));
        channel.configureBlocking(false);

        // Authenticate and configure the virtual network interface.
        handshake();
        // Now we are connected. Set the flag and show the message.
        mConnected = true;
        mHandler.sendEmptyMessage(R.string.connected);
        
        // Get VPN interface IO.
        mOutTraffic = new FileInputStream(mInterface.getFileDescriptor());
        mInTraffic  = new FileOutputStream(mInterface.getFileDescriptor());
    }

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
    
    // Polling call
    Runnable handlePackets = new Runnable() {
        // One Thread checks both: feed packets into the UDP Tunnel and fetching them from the UDP Tunnel.
        // A reverse App-Layer NAT (or whatever it is) is NOT needed for returning packets, we can write RAW packets to the VPN interface.
        @Override
        public void run() {
        	boolean InFlag = false;
        	boolean OutFlag = false;
//        	InFlag = pollIncoming();
        	OutFlag = pollOutgoing();
        	//if(OutFlag) {
        		//Toast.makeText(getApplicationContext(), "Get Packets"+(new Random().nextInt()), Toast.LENGTH_SHORT).show();
        	//}
        	if(InFlag || OutFlag) {
        		mThreadHandler.post(handlePackets);
        	} else {
        		mThreadHandler.postDelayed(handlePackets, Config.DEFAULT_POLL_MS);
        	}
        }
    };

    /** Poll outgoing packets and send them to Seller via UDP Tunnel. */
    private boolean pollOutgoing() {
        boolean packetProcessed = false;
        
        // for DeBug
        int tmp = new Random().nextInt(100);

        try {
            // Allocate the buffer for a single packet.
            ByteBuffer packet = ByteBuffer.allocate(Config.DEFAULT_MTU);
            int length = 0;
            while ((length = mOutTraffic.read(packet.array())) > 0) {
                packet.limit(length); // As-is, do not know whether is necessary.

                // Drop anything that is not TCP or UDP since reseller is not going to handle it.
                int protocol = packet.get(Config.PROTOCOL_OFFSET);
                if ((protocol != Config.PROTOCOL_TCP) && (protocol != Config.PROTOCOL_UDP)) {
                	//Toast.makeText(getApplicationContext(), "NON UDP/TCP PKT", Toast.LENGTH_SHORT).show();
                    Log.i(buyerTAG, "Dropping packet of unsupported type: " + protocol + ", length: " + length);
                    continue;
                }
                
                // for DeBug
                if(protocol == Config.PROTOCOL_UDP) {
                	String sourceAddress = (packet.get(12) & 0xFF) + "." +
                			(packet.get(13) & 0xFF) + "." +
                			(packet.get(14) & 0xFF) + "." +
                			(packet.get(15) & 0xFF);
                	String destAddress   = (packet.get(16) & 0xFF) + "." +
                			(packet.get(17) & 0xFF) + "." +
                			(packet.get(18) & 0xFF) + "." +
                			(packet.get(19) & 0xFF);
                	byte headerLength = (packet.get(0)); // format: 0100 0101
                	//short sourcePort = (short) ((packet.get(headerLength)&0xff) + (packet.get(headerLength+1)&0xff)*256);
                	//short destPort = (short) ((packet.get(headerLength+2)&0xff) + (packet.get(headerLength+3)&0xff)*256);
                	
                	
                	Toast.makeText(getApplicationContext(), tmp+" "+headerLength, Toast.LENGTH_SHORT).show();
                } else {
                	Toast.makeText(getApplicationContext(), "TCP", Toast.LENGTH_SHORT).show();
                }

                // Simply enclose it in an UDP packet and send.
/*                try {
                    mTunnel.send(new DatagramPacket(packet.array(), length));
                } catch (Exception e) {
                    Log.e(buyerTAG, "Send to seller failed: " + e.toString());
                }
*/
                // Erase and reallocate
                packet.clear();
                packet = ByteBuffer.allocate(Config.DEFAULT_MTU);
                packetProcessed = true;
            }
        } catch (Exception e) {
            Log.e(buyerTAG, "Poll outgoing failed: " + e.toString());
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
            		Log.i(buyerTAG, "Dropping packet starting with 0");
            		continue;
            	}
            	
            	int protocol = packet.get(Config.PROTOCOL_OFFSET);
            	if((protocol!=Config.PROTOCOL_TCP) && (protocol!=Config.PROTOCOL_UDP)) {
            		Log.i(buyerTAG, "Dropping packet of unsupported type: " + protocol + ", length: " + length);
            		continue;
            	}
            	
            	try {
            		mInTraffic.write(packet.array(), 0, length);
            	} catch (Exception e) {
            		Log.e(buyerTAG, "Receive from seller failed: " + e.toString());
            	}
            	
            	// Erase and reallocate
            	packet.clear();
            	packet = ByteBuffer.allocate(Config.DEFAULT_MTU);
            	packetProcessed = true;
            }
        } catch (Exception e) {
        	Log.e(buyerTAG, "Poll incoming failed: " + e.toString());
        }
    
        return packetProcessed;
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
}