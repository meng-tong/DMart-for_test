package mobile.app_for_test;

import java.io.IOException;
import java.net.DatagramPacket;
import java.net.DatagramSocket;
import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.net.SocketAddress;
import java.net.SocketException;
import java.net.SocketTimeoutException;
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
    			int headerLength = ( (packet.get(0) - 4) / 8 ) * 4;
    			short sourcePort = packet.getShort(headerLength);
    			short destPort = packet.getShort(headerLength+2);
    			short identification = packet.getShort(4);
    			
    			//is it correct? -tmeng6
    			DatagramPacket data = new DatagramPacket(packet.array(), headerLength, length);
    			//String dataString = data.toString();
    			SellerThread thread = new SellerThread(data, destAddress, sourceAddress, destPort, sourcePort, identification);
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
    	private String srcAddr;
    	private short dstPort;
    	private short srcPort;
    	private short idField;
    	private Handler threadHandler;
    	
    	public SellerThread(DatagramPacket p, String s1, String s2, short i1, short i2, short id) {
    		packetToSend = p;
    		dstAddr = s1; dstPort = i1;
    		srcAddr = s2; srcPort = i2;
    		idField = id;
    	}
    	
    	public void run() {
    		DatagramSocket socket = null;
    		try {
				socket = new DatagramSocket(dstPort);
			} catch (SocketException e) {
				Log.e(sellerTAG, "Seller failed to build new socket: " + e.toString());
			}
    		
    		try {
				socket.getChannel().configureBlocking(true);
			} catch (IOException e) {
				Log.e(sellerTAG, "Seller failed to set blocking socket: " + e.toString());
			}
    		
    		try {
				socket.setSoTimeout((int) BuyerConfig.DEFAULT_UDP_TIMEOUT);
			} catch (SocketException e) {
				Log.e(sellerTAG, "Seller failed to set socket timeout: " + e.toString());
			}
			
    		InetAddress dstInetAddr = null;
			try {
				dstInetAddr = InetAddress.getByName(dstAddr);
			} catch (UnknownHostException e) {
				Log.e(sellerTAG, "Seller failed to get inet addr: " + e.toString());
			}
    		socket.connect(dstInetAddr, dstPort);
			
			try {
				socket.send(packetToSend);
			} catch (IOException e) {
				Log.e(sellerTAG, "Seller failed to relay packet: " + e.toString());
			}
    		
			boolean timeoutFlag = false;
			int length = 0;
			while(true) {
				timeoutFlag = false;
    			
				/*byte[] packetToBackData = new byte[BuyerConfig.DEFAULT_MTU];
				DatagramPacket packetToBack = new DatagramPacket(packetToBackData, packetToBackData.length);
				try {
					socket.receive(packetToBack);
				} catch (SocketTimeoutException e) {
					timeoutFlag = true; 
				} catch (IOException e) {
					Log.e(sellerTAG, "Seller receive I/O failed: " + e.toString());
				}*/
				ByteBuffer packetToBack = ByteBuffer.allocate(BuyerConfig.DEFAULT_MTU);
				try {
					length = socket.getChannel().read(packetToBack);
				} catch (SocketTimeoutException e) {
					timeoutFlag = true; 
				} catch (IOException e) {
					Log.e(sellerTAG, "Seller receive I/O failed: " + e.toString());
				}
				
				if(timeoutFlag) {
					break;
				} else {
					//TODO: create a new header(IP + UDP)
					byte[] dataToBack = new byte[BuyerConfig.DEFAULT_MTU];
					dataToBack = packetToBack.toString().getBytes();
					packetToBack.clear();
					packetToBack = ByteBuffer.allocate(BuyerConfig.DEFAULT_MTU);
					packetToBack = ByteBuffer.wrap(dataToBack, 28, length);
					
					byte headerByte; short headerShortTmp;
					//directly cast, OK? -tmeng6
					//version + Header Length, assume 20-byte IP/UDP header
					headerByte = 84; packetToBack.put(0, headerByte); //0x00101010
					
					//TODO: Type of Service
					
					//Total Length
					headerShortTmp = (short) (28+length);
					//packetToBack.put(2, headerShortTmp); //not sure this is OK -tmeng6
					headerByte = (byte) (headerShortTmp & 0xff); packetToBack.put(2, headerByte);
					headerByte = (byte) ((headerShortTmp >> 8) & 0xff); packetToBack.put(3, headerByte);
					
					//Identification, as in the packet from Buyer
					packetToBack.putShort(4, idField);
					
					//TODO: how to get the IP Flags, and Fragment Offset
					
					//Time To Live
					headerByte = 4; packetToBack.put(8, headerByte);
					
					//Protocol
					headerByte = PROTOCOL_UDP; packetToBack.put(9, headerByte); //this class only for UDP
					
					//TODO: Header Checksum
					
					//Source and Destination Address
					packetToBack.put(dstAddr.getBytes(), 12, 8);
					packetToBack.put(srcAddr.getBytes(), 16, 8);
					
					//Source and Destination Port
					packetToBack.putShort(20, dstPort);
					packetToBack.putShort(22, srcPort);
					
					//Length
					headerShortTmp = (short) (8+length);
					//packetToBack.put(2, headerShortTmp); //not sure this is OK -tmeng6
					headerByte = (byte) (headerShortTmp & 0xff); packetToBack.put(24, headerByte);
					headerByte = (byte) ((headerShortTmp >> 8) & 0xff); packetToBack.put(25, headerByte);
					
					//TODO: Checksum
					
					try {
						mSocket.send(new DatagramPacket(packetToBack.array(), length));
					} catch (IOException e) {
						Log.e(sellerTAG, "Seller send to Buyer failed: " + e.toString());
					}
				}
    		}
			
			socket.disconnect();
			socket.close();
    	}
    }
}
