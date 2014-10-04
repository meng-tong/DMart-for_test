package mobile.app_for_test;

import java.io.IOException;
import java.net.DatagramPacket;
import java.net.DatagramSocket;
import java.net.InetAddress;
import java.net.SocketException;
import java.net.SocketTimeoutException;
import java.net.UnknownHostException;
import java.nio.ByteBuffer;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.Iterator;
import java.util.List;

import android.support.v7.app.ActionBarActivity;
import android.os.Bundle;
import android.os.Handler;
import android.util.Log;
import android.view.KeyEvent;
import android.view.Menu;
import android.view.MenuItem;
import android.view.View;
import android.widget.TextView;


public class Seller extends ActionBarActivity {
	
	private static final String sellerTAG = "Seller";
	private static final int    PROTOCOL_TCP    = 6;
    private static final int    PROTOCOL_UDP    = 17;
    //9 for sure? not 8? -tmeng6
    private static final int    PROTOCOL_OFFSET = 9;
	
	private Handler socketHandler = new Handler();
    
    private DatagramSocket mSocket;
    private int mPort = BuyerConfig.DEFAULT_PORT_NUMBER;
    
	private TextView textview = null;
	
	private HashMap<String, SellerSocket> socketMap;
	private List<DatagramPacket> packetsList;
	
	@Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_seller);
        
        textview = (TextView) findViewById(R.id.text_ip_seller);
        socketMap = new HashMap<String, SellerSocket>();
        packetsList = Collections.synchronizedList(new ArrayList<DatagramPacket>());
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
    			moveTaskToBack(true);
    		case KeyEvent.KEYCODE_HOME:
    			moveTaskToBack(true);
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
    	if(packetsList.size() <= 0) {return false;}
    	
    	//use synchronized list like this?
    	synchronized(packetsList) {
    		Iterator<DatagramPacket> itr = packetsList.iterator(); // Must be in synchronized block
    		while (itr.hasNext()) {
    			DatagramPacket newpacket = itr.next();
    			try {
    				mSocket.send(newpacket);
    			} catch (IOException e) {
    				Log.e(sellerTAG, "Seller send to Buyer failed: " + e.toString());
    			}
    		}
    		packetsList.clear();
    	}
    	
    	return true;
    }
    
    private boolean relayIncoming() {
    	boolean packetProcessed = false;
    	
    	try {
    		int length = 0;
    		byte[] packetByte = null;
    		DatagramPacket packet = null;
    		while(true) {
    			packetByte = new byte[BuyerConfig.DEFAULT_MTU];
    			packet = new DatagramPacket(packetByte, packetByte.length);
    			mSocket.receive(packet);
    			length = packet.getLength();
    			if(length <= 0) {break;}
    			
    			//Not sure if this is necessary -tmeng6
    			if(packetByte[0] == 0)
    			{
    				Log.i(sellerTAG, "Dropping packet starting with 0");
    				continue;
    			}
    			
    			int protocol = packetByte[PROTOCOL_OFFSET];
    			if(protocol == PROTOCOL_TCP) {
    				relayTCPIncoming(packetByte, length);
    			} else if(protocol == PROTOCOL_UDP) {
    				relayUDPIncoming(packetByte, length);
    			} else {
    				Log.i(sellerTAG, "Dropping packet of unsupported type: " + protocol + ", length: " + length);
    				continue;
    			}
    			/*
    			//String dataString = data.toString();
    			SellerThread thread = new SellerThread(data, destAddress, sourceAddress, destPort, sourcePort, identification);
    			//TODO: when to stop the thread -tmeng6
    			thread.start();
    			*/
    			packetProcessed = true;
    		}
    		
		} catch (IOException e) {
			Log.e(sellerTAG, "Receive from buyer failed: " + e.toString());
		}
    	
    	return packetProcessed;
    }
    
    private void relayTCPIncoming(byte[] packetByte, int length) {
    	
    }
    
    private void relayUDPIncoming(byte[] packetByte, int length) {
    	ByteBuffer packetBuffer = ByteBuffer.allocate(BuyerConfig.DEFAULT_MTU);
    	packetBuffer = ByteBuffer.wrap(packetByte);
    	
    	//processing NAT function
		//get the source/destination IP address, TODO:more efficient method needed, -tmeng6
		//DatagramPacket packetDatagram = new DatagramPacket(packet.array(), length);
		String sourceAddress = (packetBuffer.get(12) & 0xFF) + "." +
							   (packetBuffer.get(13) & 0xFF) + "." +
							   (packetBuffer.get(14) & 0xFF) + "." +
							   (packetBuffer.get(15) & 0xFF);
		String destAddress   = (packetBuffer.get(16) & 0xFF) + "." +
							   (packetBuffer.get(17) & 0xFF) + "." +
							   (packetBuffer.get(18) & 0xFF) + "." +
							   (packetBuffer.get(19) & 0xFF);
		int headerLength = ( (packetBuffer.get(0) - 4) / 8 ) * 4;
		short sourcePort = packetBuffer.getShort(headerLength);
		short destPort = packetBuffer.getShort(headerLength+2);
		short identification = packetBuffer.getShort(4);
		
		//is it correct? -tmeng6
		InetAddress dstInetAddr = null;
		try {
			dstInetAddr = InetAddress.getByName(destAddress);
		} catch (UnknownHostException e) {
			Log.e(sellerTAG, "Seller bulid dest InetAddress failed: " + e.toString());
		}
		DatagramPacket data = new DatagramPacket(packetByte, headerLength, length-headerLength,
												dstInetAddr, destPort);
		
		String buyerAddress = sourceAddress + sourcePort;
		try {
			if(socketMap.containsKey(buyerAddress)) {
				socketMap.get(buyerAddress).SendPacket(data);
			} else {
				// no existed socket corresponding to the source IP/port, build a new one
				SellerSocket newSocket = null;
				try {
					newSocket = new SellerSocket(sourceAddress, sourcePort);
				} catch (Exception e) {
					Log.e(sellerTAG, "Seller build SellerSocket failed: " + e.toString());
				}
				//first send the packet data
				newSocket.SendPacket(data);
				//start the thread of listening for incoming packets
				newSocket.start();
				//add the new socket to the map
				socketMap.put(buyerAddress, newSocket);
			}
		} catch (IOException e) {
			Log.e(sellerTAG, "Seller relay outgoing packet failed: " + e.toString());
		}
		
		packetBuffer.clear();
    }
    
    public class SellerSocket extends Thread {
		private DatagramSocket sellerSocket = null;
		private String buyerAddr;
		private short buyerPort;
    	
    	public SellerSocket(String add, short port) throws Exception {
    		buyerAddr = add; buyerPort = port;
    		if(sellerSocket == null) {
    			sellerSocket = new DatagramSocket();
    		}
    		//use blocking channel to avoid consuming computing resources
    		sellerSocket.getChannel().configureBlocking(true);
    		sellerSocket.setSoTimeout(BuyerConfig.DEFAULT_UDP_TIMEOUT);
    	}
    	
    	public void SendPacket(DatagramPacket packet) throws IOException {
    		sellerSocket.send(packet);
    	}
    	
    	public void run() {
    		boolean timeoutFlag = false;
			int length = 0;
			while(true) {
				timeoutFlag = false;
    			
				byte[] packetToBackData = new byte[BuyerConfig.DEFAULT_MTU];
				DatagramPacket packetToBack = new DatagramPacket(packetToBackData, packetToBackData.length);
				try {
					sellerSocket.receive(packetToBack);
				} catch (SocketTimeoutException e) {
					timeoutFlag = true; 
				} catch (IOException e) {
					Log.e(sellerTAG, "Seller receive I/O failed: " + e.toString());
				}
				
				if(timeoutFlag) {break;}
				else {
					String internetAddr = packetToBack.getAddress().getHostAddress();
					short internetPort = (short) packetToBack.getPort();
					
					ByteBuffer packetToBackBuffer = ByteBuffer.allocate(BuyerConfig.DEFAULT_MTU);
					packetToBackBuffer = ByteBuffer.wrap(packetToBackData, 28, length);
					
					byte headerByte; short headerShortTmp;
					//directly cast, OK? -tmeng6
					//version + Header Length, assume 20-byte IP/UDP header
					headerByte = 84; packetToBackBuffer.put(0, headerByte); //0x00101010
					
					//TODO: Type of Service
					
					//Total Length
					headerShortTmp = (short) (28+length);
					//packetToBack.put(2, headerShortTmp); //not sure this is OK -tmeng6
					headerByte = (byte) (headerShortTmp & 0xff); packetToBackBuffer.put(2, headerByte);
					headerByte = (byte) ((headerShortTmp >> 8) & 0xff); packetToBackBuffer.put(3, headerByte);
					
					//TODO: Identification, as in the packet from Buyer
					//packetToBack.putShort(4, idField);
					
					//TODO: how to get the IP Flags, and Fragment Offset
					
					//Time To Live
					headerByte = 4; packetToBackBuffer.put(8, headerByte);
					
					//Protocol
					headerByte = PROTOCOL_UDP; packetToBackBuffer.put(9, headerByte); //this class only for UDP
					
					//TODO: Header Checksum
					
					//Source and Destination Address
					packetToBackBuffer.put(internetAddr.getBytes(), 12, 8);
					packetToBackBuffer.put(buyerAddr.getBytes(), 16, 8);
					
					//Source and Destination Port
					packetToBackBuffer.putShort(20, internetPort);
					packetToBackBuffer.putShort(22, buyerPort);
					
					//Length
					headerShortTmp = (short) (8+length);
					//packetToBack.put(2, headerShortTmp); //not sure this is OK -tmeng6
					headerByte = (byte) (headerShortTmp & 0xff); packetToBackBuffer.put(24, headerByte);
					headerByte = (byte) ((headerShortTmp >> 8) & 0xff); packetToBackBuffer.put(25, headerByte);
					
					//TODO: Checksum
					
					packetsList.add(packetToBack);
				}
    		}
			
			sellerSocket.disconnect();
			sellerSocket.close();
			//TODO: close the thread
			//when the run() function returns, the thread end? -tmeng6
    	}
	}
    
    // the following code aims to create one socket for
    // each packet from Buyer, which isn't really good
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
				socket = new DatagramSocket();
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
