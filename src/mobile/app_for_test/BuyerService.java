package mobile.app_for_test;

import java.net.DatagramPacket;
import java.net.DatagramSocket;
import java.net.InetAddress;
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
import android.os.Bundle;
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
    
    //private DatagramChannel      channel		= null;
    private DatagramSocket 		 mTunnel		= null;
    private FileInputStream 	 mOutTraffic	= null; //VPN interface -> seller
    private FileOutputStream 	 mInTraffic		= null; //seller -> VPN interface
    private ParcelFileDescriptor mInterface;
    // To be obtained via Intent, it is DMartClient's job to contact WifiP2PManager and get the address.
    private String  mServerAddress 	= "192.168.49.1"; //null;
    private int     mServerPort 	= Config.DEFAULT_PORT_NUMBER;
    
    private Handler             mHandler;
    private HandlerThread       mThread;
    private Handler             mThreadHandler;
    private HandlerThread		mIncomingThread;
    private Handler				mIncomingHandler;

    private PendingIntent        mConfigureIntent;

    private boolean              mConnected       = false;
    
    // for DeBug
//    private int countPoll = 0;
    
    @Override
    public int onStartCommand(Intent intent, int flags, int startId) {
        // mHandler is only used to show toast messages.
        if (mHandler == null) {
            // I handle callback by my own handleMessage()
            mHandler = new Handler(this);
        }
        // Stop the previous session by interrupting the thread.
        // TODO: use interrupt() or other method?
        if (mThread != null) {
            Log.d(buyerTAG, "Stopping previous thread");
            mConnected = false; //TODO: make sure this
            mIncomingThread.interrupt();
            mThread.interrupt();
            mTunnel.close();
        }

        // Get server address from Intent
/*        String prefix = getPackageName();
        mServerAddress = intent.getStringExtra(prefix+".serverADDR");
        if (mServerAddress == null) {
            throw new IllegalArgumentException("Server address not found in Intent!");
        }
*/

        // Start a new session by creating a new thread.
        mThread = new HandlerThread("BuyerServiceThread");
        mThread.start();
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
                mThreadHandler.post(pollOutgoing);
                
                mIncomingThread = new HandlerThread("BuyerServiceIncomingThread");
                mIncomingThread.start();
                mIncomingHandler = new Handler(mIncomingThread.getLooper());
                mIncomingHandler.post(pollIncoming);
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

        DatagramChannel channel = DatagramChannel.open();
        mTunnel = channel.socket();
        SocketAddress mTunnelAddress = new InetSocketAddress(Config.BUYER_CLIENT_PORT);
        mTunnel.bind(mTunnelAddress);
        if (mTunnel == null) {
            throw new IllegalStateException("Datagram socket is null! Maybe too many open files in system");
        }

        // Protect the tunnel before connecting to avoid loopback.
        //check whether protect is necessary: seems not -tmeng6
/*        if (!protect(mTunnel)) {
            throw new IllegalStateException("Cannot protect the local tunnel");
        }
*/

        // Connect to the server.
        //channel.connect(new InetSocketAddress(mServerAddress, mServerPort));
        mTunnel.connect(new InetSocketAddress(mServerAddress, mServerPort));
        channel.configureBlocking(true);

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
    
    /** Poll outgoing packets and send them to Seller via UDP Tunnel. */
    Runnable pollOutgoing = new Runnable() {
    	@Override
    	public void run() {
	        boolean packetProcessed = false;
	        
	        try {
	            // Allocate the buffer for a single packet.
	            ByteBuffer packet = ByteBuffer.allocate(Config.DEFAULT_MTU);
	            int length = 0;
	            while ((length = mOutTraffic.read(packet.array())) > 0) {
	                packet.limit(length); // As-is, do not know whether is necessary.
	
	                // Drop anything that is not TCP or UDP since reseller is not going to handle it.
	                int protocol = packet.get(Config.PROTOCOL_OFFSET);
	                if (protocol == Config.PROTOCOL_TCP) {
	                	Message msg = new Message();
	                    Bundle b = new Bundle();
	                    b.putString("message", "TCP packet, do not handle");
	                    msg.setData(b);
	                    mHandler.sendMessage(msg);
	                    
	                    packet.clear();
		                packet = ByteBuffer.allocate(Config.DEFAULT_MTU);
	                	continue;
	                } else if((protocol != Config.PROTOCOL_UDP)) {
	                	Log.i(buyerTAG, "Dropping packet of unsupported type: " + protocol + ", length: " + length);
	                	packet.clear();
		                packet = ByteBuffer.allocate(Config.DEFAULT_MTU);
	                    continue;
	                }
	                
	                Log.i(buyerTAG, "SEND");
	                // Simply enclose it in an UDP packet and send.
	                try {
	                	//packet.limit(length);
	                	//mTunnel.getChannel().write(packet);
	                    mTunnel.send(new DatagramPacket(packet.array(), length, (new InetSocketAddress(mServerAddress, mServerPort))));
	                } catch (Exception e) {
	                    Log.e(buyerTAG, "Send to seller failed: " + e.toString());
	                    Message msg = new Message();
	                    Bundle b = new Bundle();
	                    b.putString("message", e.toString());
	                    msg.setData(b);
	                    mHandler.sendMessage(msg);
	                }
	
	                // Erase and reallocate
	                packet.clear();
	                packet = ByteBuffer.allocate(Config.DEFAULT_MTU);
	                packetProcessed = true;
	            }
	        } catch (Exception e) {
	            Log.e(buyerTAG, "Poll outgoing failed: " + e.toString());
	        }
	
	        if(packetProcessed) {
        		mThreadHandler.post(pollOutgoing);
        	} else {
        		mThreadHandler.postDelayed(pollOutgoing, Config.DEFAULT_POLL_MS);
        	}
    	}
    };

    /** Poll incoming packets and send them to VPN interface. */
    Runnable pollIncoming = new Runnable() {
    	@Override
    	public void run() {
    		boolean packetProcessed = false;
	        try {
	        	int length;
	        	byte[] packetByte = null;
	        	DatagramPacket packet = null;
	        	while(true) {
	        		Log.d(buyerTAG, "Ready for New Round of Incoming");
	        		length = 0;
	        		packetByte = new byte[Config.DEFAULT_MTU];
	        		packet = new DatagramPacket(packetByte, packetByte.length);
	        		mTunnel.receive(packet);
	        		length = packet.getLength();
	        		if(length <= 0) {break;}
	        		
	        		Log.i(buyerTAG, "Recv PKT-"+length);
	        		Message msg = new Message();
                    Bundle b = new Bundle();
                    b.putString("message", "Recv PKT-"+length);
                    msg.setData(b);
                    mHandler.sendMessage(msg);
	        		
	        		int protocol = packetByte[Config.PROTOCOL_OFFSET];
	            	if((protocol!=Config.PROTOCOL_TCP) && (protocol!=Config.PROTOCOL_UDP)) {
	            		Log.i(buyerTAG, "Dropping packet of unsupported type: " + protocol + ", length: " + length);
	            		continue;
	            	}
	            	
	            	try {
	            		mInTraffic.write(packetByte, 0, length);
	            	} catch (Exception e) {
	            		Log.e(buyerTAG, "Receive from seller failed: " + e.toString());
	            	}

	            	packetProcessed = true;
	        	}
	        } catch (Exception e) {
	        	Log.e(buyerTAG, "Poll incoming failed: " + e.toString());
	        }
	        
	        if(packetProcessed) {
        		mIncomingHandler.post(pollIncoming);
        	} else {
        		mIncomingHandler.postDelayed(pollIncoming, Config.DEFAULT_POLL_MS);
        	}
    	}
    };

    @Override
    public void onDestroy() {
    	if (mThread != null) {
    		mIncomingThread.quit();
            mThread.quit();
        }
        if (mConnected) {
            // TODO: send Good-bye message
            // or maybe this should be done by discovery module?
        	try {
				mInterface.close();
			} catch (IOException e) {
				Log.e(buyerTAG, "Buyer close Interface failed: " + e.toString());
			}
        	mConnected = false;
        	mTunnel.close();
        }
    }
    
    @Override
    public boolean handleMessage(Message message) {
    	if (message != null) {
    		Bundle b = message.getData();
    		String msg = b.getString("message");
    		if(msg != null) {
    			Toast.makeText(this, msg, Toast.LENGTH_SHORT).show();
    		} else {
    			Toast.makeText(this, message.what, Toast.LENGTH_SHORT).show();
    		}
        }
        return true;
    }
}