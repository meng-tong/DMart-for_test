package mobile.app_for_test;

import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.DatagramPacket;
import java.net.DatagramSocket;
import java.net.InetAddress;
import java.net.Socket;
import java.net.SocketException;
import java.net.SocketTimeoutException;
import java.net.UnknownHostException;
import java.nio.ByteBuffer;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.Iterator;
import java.util.List;
import java.util.Random;

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
	
	private HashMap<String, SellerUDPSocket> UDPsocketMap;
	private HashMap<String, SellerTCPSocket> TCPsocketMap;
	private List<DatagramPacket> packetsList;
	
	@Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_seller);
        
        textview = (TextView) findViewById(R.id.text_ip_seller);
        UDPsocketMap = new HashMap<String, SellerUDPSocket>();
        TCPsocketMap = new HashMap<String, SellerTCPSocket>();
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
		int headerLength = ( ( (int)(packetBuffer.get(0)&0xff) - 4) / 16 ) * 4;
		short sourcePort = (short) ((packetBuffer.get(headerLength)&0xff) + (packetBuffer.get(headerLength+1)&0xff)*256);
		short destPort = (short) ((packetBuffer.get(headerLength+2)&0xff) + (packetBuffer.get(headerLength+3)&0xff)*256);
		//short identification = (short) (packetBuffer.get(4) + packetBuffer.get(5)*256);
		
		int offset = (packetBuffer.get(headerLength+12)&0xff) % 16;
		int flags = packetBuffer.get(headerLength+13)&0xff;
		int SYNFlag = flags & 0x40;
		
		String tcpAddress = sourceAddress + sourcePort + destAddress + destPort;
		try {
			if(TCPsocketMap.containsKey(tcpAddress)) {
				TCPsocketMap.get(tcpAddress).handlePacket(packetByte, headerLength+offset, flags);
			} else
			{
				if(SYNFlag > 0) {
					// build the TCP socket
					SellerTCPSocket newSocket = null;
					newSocket = new SellerTCPSocket(sourceAddress, sourcePort,
												destAddress, destPort);
					newSocket.handlePacket(packetByte, headerLength+offset, flags);
					newSocket.start();
					TCPsocketMap.put(tcpAddress, newSocket);
				} else {
					// impossible/ERROR: request for an in-exist TCP connection
					Log.i(sellerTAG, "seller receives illegle request for in-exist TCP");
				}
			}
		} catch(IOException e) {
			Log.e(sellerTAG, "Seller relay outgoing TCP packet failed: " + e.toString());
		}
		
		packetBuffer.clear();
    }
    
    public class SellerTCPSocket extends Thread {
    	private String buyerAddr;
    	private short buyerPort;
    	private String internetAddr;
    	private short internetPort;
    	
    	private Socket sellerTCPSocket = null;
    	private OutputStream outTraffic;
    	private InputStream inTraffic;
    	
    	private int seqNo;
    	private int ackNo;
    	private short buyerWindow;
    	
    	public SellerTCPSocket(String srcAdd, short srcPort, String dstAdd, short dstPort) throws IOException {
    		buyerAddr = srcAdd; buyerPort = srcPort;
    		internetAddr = dstAdd; internetPort = dstPort;
    		
    		seqNo = new Random().nextInt(100);
    		ackNo = -1;
    		buyerWindow = 32;
    		
    		if(sellerTCPSocket == null) {
    			sellerTCPSocket = new Socket(internetAddr, internetPort);
    		}
    		OutputStream outTraffic = sellerTCPSocket.getOutputStream();
    		InputStream inTraffic = sellerTCPSocket.getInputStream();
    	}
    	
    	public void handlePacket(byte[] packet, int offset, int flags) throws IOException {
    		int SYNFlag = flags & 0x40;
    		int FINFlag = flags & 0x80;
    		int ACKFlag = flags & 0x08;
    	}
    	
    	public void run() {
    		
    	}
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
		int headerLength = ( ( (int)(packetBuffer.get(0)&0xff) - 4) / 16 ) * 4;
		short sourcePort = (short) ((packetBuffer.get(headerLength)&0xff) + (packetBuffer.get(headerLength+1)&0xff)*256);
		short destPort = (short) ((packetBuffer.get(headerLength+2)&0xff) + (packetBuffer.get(headerLength+3)&0xff)*256);
		//short identification = (short) (packetBuffer.get(4) + packetBuffer.get(5)*256);
		
		//is it correct? -tmeng6
		InetAddress dstInetAddr = null;
		try {
			dstInetAddr = InetAddress.getByName(destAddress);
		} catch (UnknownHostException e) {
			Log.e(sellerTAG, "Seller bulid dest InetAddress failed: " + e.toString());
		}
		DatagramPacket data = new DatagramPacket(packetByte, headerLength+8, length-headerLength-8,
												dstInetAddr, destPort);
		
		String buyerAddress = sourceAddress + sourcePort;
		try {
			if(UDPsocketMap.containsKey(buyerAddress)) {
				UDPsocketMap.get(buyerAddress).SendPacket(data);
			} else {
				// no existed socket corresponding to the source IP/port, build a new one
				SellerUDPSocket newSocket = null;
				newSocket = new SellerUDPSocket(sourceAddress, sourcePort);
				
				//first send the packet data
				//Q: will data be sent before DatagramSocket is ready? -tmeng6
				newSocket.SendPacket(data);
				//start the thread of listening for incoming packets
				newSocket.start();
				//add the new socket to the map
				UDPsocketMap.put(buyerAddress, newSocket);
			}
		} catch (IOException e) {
			Log.e(sellerTAG, "Seller relay outgoing UDP packet failed: " + e.toString());
		}
		
		packetBuffer.clear();
    }
    
    public class SellerUDPSocket extends Thread {
		private DatagramSocket sellerUDPSocket = null;
		private String buyerAddr;
		private short buyerPort;
    	
    	public SellerUDPSocket(String add, short port) throws IOException {
    		buyerAddr = add; buyerPort = port;
    		if(sellerUDPSocket == null) {
    			sellerUDPSocket = new DatagramSocket();
    		}
    		//use blocking channel to avoid consuming computing resources
    		sellerUDPSocket.getChannel().configureBlocking(true);
    		sellerUDPSocket.setSoTimeout(BuyerConfig.DEFAULT_UDP_TIMEOUT);
    	}
    	
    	public SellerUDPSocket(String add, short port, DatagramPacket packet) throws IOException {
    		buyerAddr = add; buyerPort = port;
    		if(sellerUDPSocket == null) {
    			sellerUDPSocket = new DatagramSocket();
    		}
    		//use blocking channel to avoid consuming computing resources
    		sellerUDPSocket.getChannel().configureBlocking(true);
    		sellerUDPSocket.setSoTimeout(BuyerConfig.DEFAULT_UDP_TIMEOUT);
    		
    		sellerUDPSocket.send(packet);
    	}
    	
    	public void SendPacket(DatagramPacket packet) throws IOException {
    		sellerUDPSocket.send(packet);
    	}
    	
    	public void run() {
    		boolean timeoutFlag = false;
			int length = 0;
			while(true) {
				timeoutFlag = false;
    			
				byte[] packetToBackData = new byte[BuyerConfig.DEFAULT_MTU];
				DatagramPacket packetToBack = new DatagramPacket(packetToBackData, packetToBackData.length);
				try {
					sellerUDPSocket.receive(packetToBack);
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
					packetToBackBuffer.putShort(4, (short)0);
					
					//TODO: how to get the IP Flags, and Fragment Offset
					
					//Time To Live
					headerByte = 4; packetToBackBuffer.put(8, headerByte);
					
					//Protocol
					headerByte = PROTOCOL_UDP; packetToBackBuffer.put(9, headerByte); //this class only for UDP
					
					//TODO: Header Checksum
					
					//Source and Destination Address
					int tmpint;
					String[] tmpAdd = internetAddr.split("\\.");
					tmpint = Integer.parseInt(tmpAdd[0]); headerByte = (byte) (tmpint); packetToBackBuffer.put(12, headerByte);
					tmpint = Integer.parseInt(tmpAdd[1]); headerByte = (byte) (tmpint); packetToBackBuffer.put(13, headerByte);
					tmpint = Integer.parseInt(tmpAdd[2]); headerByte = (byte) (tmpint); packetToBackBuffer.put(14, headerByte);
					tmpint = Integer.parseInt(tmpAdd[3]); headerByte = (byte) (tmpint); packetToBackBuffer.put(15, headerByte);
					tmpAdd = buyerAddr.split("\\.");
					tmpint = Integer.parseInt(tmpAdd[0]); headerByte = (byte) (tmpint); packetToBackBuffer.put(16, headerByte);
					tmpint = Integer.parseInt(tmpAdd[1]); headerByte = (byte) (tmpint); packetToBackBuffer.put(17, headerByte);
					tmpint = Integer.parseInt(tmpAdd[2]); headerByte = (byte) (tmpint); packetToBackBuffer.put(18, headerByte);
					tmpint = Integer.parseInt(tmpAdd[3]); headerByte = (byte) (tmpint); packetToBackBuffer.put(19, headerByte);
					
					//Source and Destination Port
					headerByte = (byte) (internetPort & 0xff); packetToBackBuffer.put(20, headerByte);
					headerByte = (byte) ((internetPort >> 8) & 0xff); packetToBackBuffer.put(21, headerByte);
					headerByte = (byte) (buyerPort & 0xff); packetToBackBuffer.put(22, headerByte);
					headerByte = (byte) ((buyerPort >> 8) & 0xff); packetToBackBuffer.put(23, headerByte);
					
					//Length
					headerShortTmp = (short) (8+length);
					//packetToBack.put(2, headerShortTmp); //not sure this is OK -tmeng6
					headerByte = (byte) (headerShortTmp & 0xff); packetToBackBuffer.put(24, headerByte);
					headerByte = (byte) ((headerShortTmp >> 8) & 0xff); packetToBackBuffer.put(25, headerByte);
					
					//TODO: Checksum
					
					packetsList.add(packetToBack);
				}
    		}
			
			sellerUDPSocket.disconnect();
			sellerUDPSocket.close();
			//TODO: refine when to close the socket
			//TODO: close the thread to avoid seller use a closed sellerUDPSocket
			//when the run() function returns, the thread end? -tmeng6
    	}
	}
    
    // the following code aims to create one socket for
    // each packet from Buyer, which isn't really good
    // FIXME: putShort cannot be used
    public class SellerThread extends Thread {
    	private DatagramPacket packetToSend;
    	private String dstAddr;
    	private String srcAddr;
    	private short dstPort;
    	private short srcPort;
    	private short idField;
    	
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
					//wrong to use getBytes() here!!! -tmeng6
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
