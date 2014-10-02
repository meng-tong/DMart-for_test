package mobile.app_for_test;

import java.io.IOException;
import java.net.DatagramPacket;
import java.net.DatagramSocket;
import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.net.SocketAddress;
import java.net.SocketException;
import java.net.UnknownHostException;
import java.nio.ByteBuffer;
import java.nio.channels.DatagramChannel;
import java.util.Random;

import android.support.v7.app.ActionBarActivity;
import android.text.format.Time;
import android.content.Intent;
import android.os.Bundle;
import android.os.Handler;
import android.util.Log;
import android.view.KeyEvent;
import android.view.Menu;
import android.view.MenuItem;
import android.view.View;
import android.widget.TextView;


public class Seller extends ActionBarActivity {
	
	private int numOutgoingSockets = 0;
	
	private static final String sellerTAG = "Seller";
	private static final int    PROTOCOL_TCP    = 6;
    private static final int    PROTOCOL_UDP    = 17;
    //9 for sure? not 8? -tmeng6
    private static final int    PROTOCOL_OFFSET = 9;
	
	private Handler socketHandler = new Handler();
    
    private DatagramSocket mSocket;
    private int mPort = BuyerConfig.DEFAULT_PORT_NUMBER;
    
	private TextView textview = null;
	
    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_seller);
        
        textview = (TextView) findViewById(R.id.text_ip_seller);
    }
    
    @Override
    public boolean onCreateOptionsMenu(Menu menu) {
        // Inflate the menu; this adds items to the action bar if it is present.
        getMenuInflater().inflate(R.menu.seller, menu);
        return true;
    }

    @Override
    public boolean onOptionsItemSelected(MenuItem item) {
        // Handle action bar item clicks here. The action bar will
        // automatically handle clicks on the Home/Up button, so long
        // as you specify a parent activity in AndroidManifest.xml.
        int id = item.getItemId();
        if (id == R.id.action_settings) {
            return true;
        }
        return super.onOptionsItemSelected(item);
    }
    
    @Override
    public boolean onKeyDown(int KeyCode, KeyEvent event) {
    	switch(KeyCode)
    	{
    		case KeyEvent.KEYCODE_BACK:
    			moveTaskToBack(false);
    		case KeyEvent.KEYCODE_HOME:
    			moveTaskToBack(false);
    		case KeyEvent.KEYCODE_MENU:
    			break;
    	}
    	return super.onKeyDown(KeyCode, event);
    }
    
    public void onClick1(View view)
	{
		//String newtext = "127.0.0.1: ";
		//int randint = new Random().nextInt(10);
		//newtext = newtext + Integer.toString(randint);
		//textview.setText(newtext);
    	//socketThread = new Thread("SellerThread");
        //socketThread.run();
        socketHandler.post(waitForVpnConnection);
	}
    
    Runnable waitForVpnConnection = new Runnable() {
    	@Override
        public void run() {
            Log.i(sellerTAG, "Beginning waiting ...");
            
            try {
				mSocket = new DatagramSocket(mPort);
			} catch (SocketException e) {
				Log.e(sellerTAG, "Building DatagramSocket Failed: " + e.toString());
			}
            
            //does that guarantee unblocking? -tmeng6
            try {
				mSocket.getChannel().configureBlocking(false);
			} catch (IOException e) {
				Log.e(sellerTAG, "Blocking configure of Socket to Buyer Failed: " + e.toString());
			}
            
            socketHandler.post(handlePackets);
        }
    };
    
    Runnable handlePackets = new Runnable() {
    	@Override
    	public void run() {
    		boolean InFlag, OutFlag;
        	InFlag = relayIncoming();
        	OutFlag = relayOutgoing();
        	if(InFlag || OutFlag) {
        		socketHandler.post(handlePackets);
        	} else {
        		socketHandler.postDelayed(handlePackets, BuyerConfig.DEFAULT_POLL_MS);
        	}
    	}
    };
    
    private boolean relayOutgoing() {
    	boolean packetProcessed = false;
    	
    	return packetProcessed;
    }
    
    private boolean relayIncoming() {
    	boolean packetProcessed = false;
    	
    	try {
    		int length = 0;
    		/*byte[] packetByte = null;
    		DatagramPacket packet = null;
    		do {
    			packetByte = new byte[BuyerConfig.DEFAULT_MTU];
    			packet = new DatagramPacket(packetByte, packetByte.length);
    			mSocket.receive(packet);
    			length = packet.getLength();
    			
    			packet.getAddress();
    			
    			byte[] data = new byte[BuyerConfig.DEFAULT_MTU];
    			data = packet.getData();
    		}while(length > 0);*/
    		
    		ByteBuffer packet = ByteBuffer.allocate(BuyerConfig.DEFAULT_MTU);
    		// for unblocking channel, read() can return 0? -tmeng6
    		while((length=mSocket.getChannel().read(packet)) > 0) {
    			//Not sure if this is necessary -tmeng6
    			if(packet.get(0) == 0)
    			{
    				Log.i(sellerTAG, "Dropping packet starting with 0");
    				continue;
    			}
    			
    			int protocol = packet.get(PROTOCOL_OFFSET);
    			if((protocol!=PROTOCOL_TCP) && (protocol!=PROTOCOL_UDP))
    			{
    				Log.i(sellerTAG, "Dropping packet of unsupported type: " + protocol + ", length: " + length);
    				continue;
    			}
    			
    			//processing NAT function
    			//get the source/destination IP address, TODO:more efficient method needed, -tmeng6
    			//DatagramPacket packetDatagram = new DatagramPacket(packet.array(), length);
    			String sourceAddress = (packet.get(12) & 0xFF) + "." +
    								   (packet.get(13) & 0xFF) + "." +
    								   (packet.get(14) & 0xFF) + "." +
    								   (packet.get(15) & 0xFF);
    			String destAddress   = (packet.get(16) & 0xFF) + "." +
    								   (packet.get(17) & 0xFF) + "." +
    								   (packet.get(18) & 0xFF) + "." +
    								   (packet.get(19) & 0xFF);
    			int headerLength = (packet.get(0) - 4) / 8;
    			int sourcePort = packet.get(headerLength)*16 + packet.get(headerLength+1);
    			int destPort = packet.get(headerLength+2)*16 + packet.get(headerLength+3);
    			
    			//is it correct? -tmeng6
    			DatagramPacket data = new DatagramPacket(packet.array(), headerLength, length);
    			//String dataString = data.toString();
    			SellerThread thread = new SellerThread(data, destAddress, destPort);
    			//TODO: when to stop the thread -tmeng6
    			//what if two packets from Buyer aim at the same destination? -tmeng6
    			thread.start();
    			
    			packet.clear();
    			packet = ByteBuffer.allocate(BuyerConfig.DEFAULT_MTU);
    			packetProcessed = true;
    		}
    		
		} catch (IOException e) {
			Log.e(sellerTAG, "Receive from buyer failed: " + e.toString());
		}
    	
    	return packetProcessed;
    }
    
    public class SellerThread extends Thread {
    	private DatagramPacket packetToSend;
    	private String dstAddr;
    	private int dstPort;
    	private Handler threadHandler;
    	
    	public SellerThread(DatagramPacket p, String add, int port) {
    		packetToSend = p;
    		dstAddr = add; dstPort = port;
    	}
    	
    	public void run() {
    		DatagramSocket socket = null;
    		try {
				socket = new DatagramSocket(dstPort);
			} catch (SocketException e) {
				Log.e(sellerTAG, "Seller failed to build new socket: " + e.toString());
			}
    		
    		InetAddress dstInetAddr = null;
			try {
				dstInetAddr = InetAddress.getByName(dstAddr);
			} catch (UnknownHostException e) {
				Log.e(sellerTAG, "Seller failed to get inet addr: " + e.toString());
			}
			
			socket.connect(dstInetAddr, dstPort);
			try {
				socket.getChannel().configureBlocking(false);
			} catch (IOException e) {
				Log.e(sellerTAG, "Seller failed to set unblocking socket: " + e.toString());
			}
			
			try {
				socket.send(packetToSend);
			} catch (IOException e) {
				Log.e(sellerTAG, "Seller failed to relay packet: " + e.toString());
			}
    		
			long startTime = System.currentTimeMillis();
			long inactiveDuration;
			while(true) {
    			//TODO: keep receiving,
				//and update startTime if a packet is received
				
				inactiveDuration = System.currentTimeMillis() - startTime;
				if(inactiveDuration > BuyerConfig.DEFAULT_UDP_TIMEOUT) {
					break;
				} else {
				}
    		}
			
			socket.disconnect();
			socket.close();
    	}
    }
}
