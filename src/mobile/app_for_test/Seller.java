package mobile.app_for_test;

import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.DatagramPacket;
import java.net.DatagramSocket;
import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.net.Socket;
import java.net.SocketAddress;
import java.net.SocketException;
import java.net.SocketTimeoutException;
import java.net.UnknownHostException;
import java.nio.ByteBuffer;
import java.nio.channels.DatagramChannel;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.Iterator;
import java.util.List;
import java.util.Map;
import java.util.Map.Entry;
import java.util.Random;
import java.util.Set;

import android.support.v7.app.ActionBarActivity;
import android.content.Intent;
import android.os.Bundle;
import android.os.Handler;
import android.os.HandlerThread;
import android.os.Message;
import android.util.Log;
import android.view.KeyEvent;
import android.view.Menu;
import android.view.MenuItem;
import android.view.View;
import android.widget.TextView;
import android.widget.Toast;


public class Seller extends ActionBarActivity implements Handler.Callback {
	
	private static final String sellerTAG = "Seller";
	
	private Handler mainHandler;
	private HandlerThread socketThread;
    private Handler socketHandler;
    private HandlerThread outgoingThread;
    private Handler outgoingHandler;
    
    private DatagramSocket mSocket;
    private static int mPort = Config.DEFAULT_PORT_NUMBER;
    
	private TextView textview = null;
	
	private Map<String, SellerUDPSocket> UDPsocketMap;
	private List<DatagramPacket> udpPacketsList; //TCP control packets also goes in here
	
	private Map<String, SellerTCPSocket> TCPsocketMap;
	private short sellerFlowControlWindow;
	
	private boolean isConnected = false;
	
	private int countPoll = 0;
	
	@Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_seller);
        
        textview = (TextView) findViewById(R.id.text_ip_seller);
        UDPsocketMap = Collections.synchronizedMap(new HashMap<String, SellerUDPSocket>());
        udpPacketsList = Collections.synchronizedList(new ArrayList<DatagramPacket>());
        
        TCPsocketMap = Collections.synchronizedMap(new HashMap<String, SellerTCPSocket>());        
        sellerFlowControlWindow = Config.DEFAULT_MTU;
    }
    
    @Override
    public boolean onCreateOptionsMenu(Menu menu) {
    	menu.add(Menu.NONE, 1, 1, "Back");
		menu.add(Menu.NONE, 2, 2, "Stop");
		menu.add(Menu.NONE, 3, 3, "Quit");
		return true;
    }

    @Override
    public boolean onOptionsItemSelected(MenuItem item) {
    	int id = item.getItemId();
		switch(id) {
			case 1:
				final Intent intent = new Intent(Seller.this, Main.class);
				new Thread(new Runnable() {
		            public void run() {
		            	startActivity(intent);
		        		finish();
		            }
		        }).start();
				break;
			case 2:
				if(socketThread != null) {
		            socketThread.quit();
		        }
		    	if(outgoingThread != null) {
		    		outgoingThread.quit();
		    	}
		    	if(isConnected) {
		    		isConnected = false;
		    		mSocket.close();
		    	}
				break;
			case 3:
				//TODO: add Quit operations
				break;
		}
		return true;
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
    	if (mainHandler == null) {
            mainHandler = new Handler(this);
        }
    	
    	if(socketThread != null) {
            Log.d(sellerTAG, "Stopping previous thread");
            socketThread.interrupt();
        }
    	if(outgoingThread != null) {
    		outgoingThread.interrupt();
    	}
    	if(isConnected) {
    		isConnected = false;
    		mSocket.close();
    	}
    	
    	socketThread = new HandlerThread("SellerThread");
        socketThread.start(); // the difference from start()?(tmeng6)
        socketHandler = new Handler(socketThread.getLooper());
        socketHandler.post(waitForVpnConnection);
	}
    
    Runnable waitForVpnConnection = new Runnable() {
    	@Override
        public void run() {
            Log.i(sellerTAG, "Prepare Seller Socket ...");
            
            try {
				DatagramChannel channel = DatagramChannel.open();
				mSocket = channel.socket();
				SocketAddress mSocketAddress = new InetSocketAddress(mPort);
		        mSocket.bind(mSocketAddress);
		        isConnected = true;
		        
		        channel.configureBlocking(true);
		        //mSocket.setSoTimeout(10);
			} catch (IOException e) {
				Log.e(sellerTAG, "Seller Building DatagramChannel to Buyer Failed: " + e.toString());
			}
            
			Log.i(sellerTAG, "Seller Socket ready");
			
            socketHandler.post(relayIncoming);
            
            outgoingThread = new HandlerThread("SellerOutgoingThread");
            outgoingThread.start();
            outgoingHandler = new Handler(outgoingThread.getLooper());
            outgoingHandler.post(relayOutgoing);

        }
    };
    
    Runnable relayOutgoing = new Runnable() {
    	@Override
    	public void run() {
    		boolean TCPFlag = false; 
//    			TCPFlag = relayTCPOutgoing();
    		boolean UDPFlag = relayUDPOutgoing();
    		
    		if(TCPFlag || UDPFlag) {
    			outgoingHandler.post(relayOutgoing);
    		} else {
    			outgoingHandler.postDelayed(relayOutgoing, Config.DEFAULT_POLL_MS);
    		}
    	}
    };
    
    private boolean relayTCPOutgoing() {
    	if(TCPsocketMap.size() == 0) {return false;}
    	
    	boolean result = false;
    	Set<Entry<String,SellerTCPSocket>> entries = TCPsocketMap.entrySet();
    	//Set<String> setOfMap = TCPsocketMap.keySet();
    	synchronized(TCPsocketMap) {
    		Iterator<Entry<String,SellerTCPSocket>> itr = entries.iterator(); // Must be in synchronized block
    	      while (itr.hasNext()) {
    	          Entry<String,SellerTCPSocket> en = itr.next();
    	          SellerTCPSocket tcpSocket = en.getValue();
    	          boolean tmpresult = tcpSocket.handleSegments();
    	          result = (result || tmpresult);
    	      }
    	}
    	
    	return result;
    }
    
    private boolean relayUDPOutgoing() {
    	if(udpPacketsList.size() == 0) {return false;}
    	
    	//use synchronized list like this?
    	synchronized(udpPacketsList) {
    		Iterator<DatagramPacket> itr = udpPacketsList.iterator(); // Must be in synchronized block
    		while (itr.hasNext()) {
    			DatagramPacket newpacket = itr.next();
    			Log.i(sellerTAG, "Seller needs to send "+newpacket.getLength());
    			try {
    				mSocket.send(newpacket);
    			} catch (IOException e) {
    				Log.e(sellerTAG, "Seller send to Buyer failed: " + e.toString());
    			}
    		}
    		udpPacketsList.clear();
    	}
    	return true;
    }
    
    Runnable relayIncoming = new Runnable() {
    	@Override
    	public void run() {
    		Log.d(sellerTAG, countPoll+"-relayIncoming");
    		
	    	boolean packetProcessed = false;
	    	
	    	try {
	    		int length;
	    		byte[] packetByte = null;
	    		DatagramPacket packet = null;
	    		while(true) {
	    			length = 0;
	    			packetByte = new byte[Config.DEFAULT_MTU];
	    			packet = new DatagramPacket(packetByte, packetByte.length);
	    			mSocket.receive(packet);
	    			length = packet.getLength();
	    			if(length <= 0) {break;}
	    			
	    			int protocol = packetByte[Config.PROTOCOL_OFFSET];
	    			if(protocol == Config.PROTOCOL_TCP) {
	    				// currently we do not consider fragmentation for packets from Buyer
//	    				relayTCPIncoming(packetByte, length);
	    			} else if(protocol == Config.PROTOCOL_UDP) {
	    				relayUDPIncoming(packetByte, length);
	    			} else {
	    				Log.i(sellerTAG, "Dropping packet of unsupported type: " + protocol + ", length: " + length);
	    				continue;
	    			}
	    			
	    			packetProcessed = true;
	    		}
	    		
			} catch (SocketTimeoutException e) {
			} catch (IOException e) {
				Log.e(sellerTAG, "Receive from buyer failed: " + e.toString());
			}
	    	
	    	//for DeBug
	    	countPoll += 1;
	    	
	    	if(packetProcessed) {
        		socketHandler.post(relayIncoming);
        	} else {
        		socketHandler.postDelayed(relayIncoming, Config.DEFAULT_POLL_MS);
        	}
    	}
    };
    
    private void relayTCPIncoming(byte[] packetByte, int length) {
    	//processing NAT function, get the source/destination IP address
		String sourceAddress = (packetByte[12] & 0xFF) + "." +
							   (packetByte[13] & 0xFF) + "." +
							   (packetByte[14] & 0xFF) + "." +
							   (packetByte[15] & 0xFF);
		String destAddress   = (packetByte[16] & 0xFF) + "." +
							   (packetByte[17] & 0xFF) + "." +
							   (packetByte[18] & 0xFF) + "." +
							   (packetByte[19] & 0xFF);
		int headerLength = ( ( (int)(packetByte[0]&0xff) - 4) / 16 ) * 4;
		short sourcePort = (short) ((packetByte[headerLength]&0xff) + (packetByte[headerLength+1]&0xff)*256);
		short destPort = (short) ((packetByte[headerLength+2]&0xff) + (packetByte[headerLength+3]&0xff)*256);
		//short identification = (short) (packetBuffer.get(4) + packetBuffer.get(5)*256);
		
		int offset = (packetByte[headerLength+12]&0xff) * 4;
		int flags = packetByte[headerLength+13]&0xff;
		int SYNFlag = flags & 0x40;
		int ACKFlag = flags & 0x08;
		
		String tcpAddress = sourceAddress + sourcePort + destAddress + destPort;
		try {
			if(TCPsocketMap.containsKey(tcpAddress)) {
				TCPsocketMap.get(tcpAddress).handlePacket(packetByte, headerLength, offset, length, flags);
			} else
			{
				if(SYNFlag>0 && ACKFlag<=0) {
					// build the TCP socket
					SellerTCPSocket newSocket = null;
					newSocket = new SellerTCPSocket(sourceAddress, sourcePort,
												destAddress, destPort);
					newSocket.start();
					newSocket.handlePacket(packetByte, headerLength, offset, length, flags);
					TCPsocketMap.put(tcpAddress, newSocket);
				} else {
					// impossible/ERROR: request for an in-exist TCP connection
					Log.e(sellerTAG, "seller receives illegle request for in-exist TCP");
				}
			}
		} catch(IOException e) {
			Log.e(sellerTAG, "Seller relay outgoing TCP packet failed: " + e.toString());
		}
    }
    
    public class SellerTCPSocket extends Thread {
    	private String buyerAddr;
    	private short buyerPort;
    	private String internetAddr;
    	private short internetPort;
    	
    	private Socket sellerTCPSocket = null;
    	private OutputStream outTraffic;
    	private InputStream inTraffic;
    	
    	private List<byte[]> byteBufferList;
    	private List<Integer> byteOffsetList;
    	private List<DatagramPacket> tcpPacketsList;
    	private List<Integer> tcpSeqNoList;
    	private List<Long> tcpTimestampsList;
    	int lastAckNo;
    	int countAck;
    	
    	private int seqNoCumulative;
    	private int seqNoAcked;
    	private int ackNo;
    	private boolean ackNeededFlag;
    	
    	private short congestionWindow;
    	private short buyerFlowControlWindow;
    	
    	private int state;
    	
    	public SellerTCPSocket(String srcAdd, short srcPort, String dstAdd, short dstPort) throws IOException {
    		buyerAddr = srcAdd; buyerPort = srcPort;
    		internetAddr = dstAdd; internetPort = dstPort;
    		seqNoCumulative = new Random().nextInt(100); //randomly generate sequence number
    		seqNoAcked = seqNoCumulative;
    		ackNo = 0;
    		ackNeededFlag = false;
    		congestionWindow = Config.DEFAULT_MTU;
    		state = Config.TCP_STATE_SYN;
    		if(sellerTCPSocket == null) {
    			sellerTCPSocket = new Socket(internetAddr, internetPort);
    		}
    		outTraffic = sellerTCPSocket.getOutputStream();
    		inTraffic = sellerTCPSocket.getInputStream();
    		
    		byteBufferList = Collections.synchronizedList(new ArrayList<byte[]>());
    		byteOffsetList = Collections.synchronizedList(new ArrayList<Integer>());
    		tcpPacketsList = Collections.synchronizedList(new ArrayList<DatagramPacket>());
    		tcpSeqNoList = Collections.synchronizedList(new ArrayList<Integer>());
    		tcpTimestampsList = Collections.synchronizedList(new ArrayList<Long>());
    		lastAckNo = countAck = -1;
    	}
    	
    	public boolean handleSegments() {
    		if(byteBufferList.size() == 0) {return false;}
    		
    		boolean result = false;
    		boolean return_flag = false;
    		int occupiedSize = 0;
    		
    		//remove those ack-ed packets
    		for(int i=0;i<tcpPacketsList.size();++i) {
    			int tmpSeqNo = tcpSeqNoList.get(i);
    			if(tmpSeqNo < seqNoAcked) {
    				tcpPacketsList.remove(i);
    				tcpSeqNoList.remove(i);
    				tcpTimestampsList.remove(i);
    				i -= 1;
				}
    		}
    		//detect timeout and re-transmit
    		for(int i=0;i<tcpPacketsList.size();++i) {
    			long oldStamp = tcpTimestampsList.get(i);
    			long currentStamp = System.currentTimeMillis();
    			if(currentStamp - oldStamp > Config.TCP_LOST_TIMEOUT) {
    				occupiedSize += tcpPacketsList.get(i).getLength();
        			if(occupiedSize > Math.min(congestionWindow&0xff, buyerFlowControlWindow&0xff)) {
        				return result;
    				} else {
    					tcpTimestampsList.set(i, currentStamp);
    					try {
							mSocket.send(tcpPacketsList.get(i));
						} catch (IOException e) {
							Log.e(sellerTAG, "Seller send PKT to Buyer Failed: " + e.toString());
						}
    					result = true;
    					//TODO: update congestionWidow
    				}
    			}
    		}
    		
    		int availableSize = Math.min(congestionWindow&0xff, buyerFlowControlWindow&0xff) -
    								occupiedSize;
			while(byteBufferList.size()>0) {
				byte[] newPKTDataByte = byteBufferList.get(0);
				int newOffset = byteOffsetList.get(0);
				int newLength = (short) newPKTDataByte.length;
				
				int newPKTSize = Math.min(newLength, availableSize);
				if(newPKTSize > Config.DEFAULT_MTU) {newPKTSize = Config.DEFAULT_MTU;}
				
				// guarantee newPKTSize is multiple of 8 only if it is the last segment
				if(newPKTSize < newLength) {newPKTSize = newPKTSize - (newPKTSize%8);}
				if( (newPKTSize<20&&newOffset==0) || (newPKTSize<=0) ) {
					return result;
				}
				
				byte[] IPHeader;
				if(newPKTSize == newLength) {
					IPHeader = createIPHeader((short)newPKTSize, false, (short)(newOffset/8));
				} else {
					IPHeader = createIPHeader((short)newPKTSize, true, (short)(newOffset/8));
				}
				byte[] newPKTByte = new byte[newPKTSize+20];
				System.arraycopy(IPHeader, 0, newPKTByte, 0, 20);
				System.arraycopy(newPKTDataByte, 0, newPKTByte, 20, newPKTSize);
				DatagramPacket newPKT = new DatagramPacket(newPKTByte, newPKTSize+20);
				tcpPacketsList.add(newPKT);
				//TODO: handle Sequence number
				tcpSeqNoList.add(seqNoCumulative);
				seqNoCumulative += newPKTSize;
				tcpTimestampsList.add(System.currentTimeMillis());
				try {
					mSocket.send(newPKT);
				} catch (IOException e) {
					Log.e(sellerTAG, "Seller send PKT to Buyer failed: " + e.toString());
				}
				result = true;
				
				if(newPKTSize == newLength) {
					byteBufferList.remove(0);
					byteOffsetList.remove(0);
				} else {
					byte[] remainingPKTByte = new byte[newLength - newPKTSize];
					System.arraycopy(newPKTDataByte, newPKTSize, remainingPKTByte, 0, newLength-newPKTSize);
					byteBufferList.set(0, remainingPKTByte);
					byteOffsetList.set(0, newOffset+newPKTSize);
				}
				
				occupiedSize += newPKTSize;
				availableSize = Math.min(congestionWindow&0xff, buyerFlowControlWindow&0xff)
						- occupiedSize;
			}
    		
    		return result;
    	}
    	
    	public short getCongestionWindow() {return congestionWindow;}
    	public short getFlowControlWindow() {return buyerFlowControlWindow;}
    	
    	public void handlePacket(byte[] packet, int IPOffset, int TCPOffset, int length, int flags) throws IOException {
    		int SYNFlag = flags & 0x40;
    		int FINFlag = flags & 0x80;
    		int ACKFlag = flags & 0x08;
    		
    		int buyerSequenceNo = (packet[IPOffset+4]&0xff) + (packet[IPOffset+5]&0xff)*256 +
					(packet[IPOffset+6]&0xff)*65536 + (packet[IPOffset+7]&0xff)*16777216;
    		int buyerAckNo = (packet[IPOffset+8]&0xff) + (packet[IPOffset+9]&0xff)*256 +
					(packet[IPOffset+10]&0xff)*65536 + (packet[IPOffset+11]&0xff)*16777216;
    		buyerFlowControlWindow = (short) ((packet[IPOffset+14]&0xff) + (packet[IPOffset+15]&0xff)*256);
    		
    		if(SYNFlag>0 && ACKFlag<=0 && state==Config.TCP_STATE_SYN) {
    			// SYN received, SYNACK needed
    			ackNo = buyerSequenceNo + 1;
    			DatagramPacket packetSYNACK = createPKT(Config.PKT_TYPE_SYNACK);
    			udpPacketsList.add(packetSYNACK); //control packets, goes into udp packets list
    		} else if(SYNFlag<=0 && ACKFlag>0 && state==Config.TCP_STATE_SYN) {
    			// ACK for SYNACK received, TCP connection established
    			if(buyerAckNo == seqNoAcked+1) {
    				seqNoAcked += 1;
    				seqNoCumulative = seqNoAcked;
    				state = Config.TCP_STATE_ACTIVE;
    			}
    		} else if(SYNFlag<=0 && FINFlag<=0 &&
    			(state==Config.TCP_STATE_SYN||state==Config.TCP_STATE_ACTIVE)) {
    			// normal data packet or data ACK
    			int dataLength = length - IPOffset - TCPOffset;
    			if(buyerSequenceNo == ackNo)
    			{
    				ackNo += dataLength;
    				if(seqNoAcked < buyerAckNo) {
    					seqNoAcked = buyerAckNo;
    				}
				}
    			if(ACKFlag > 0) { // the packet is an DATA ACK
	    			//TODO: Congestion control
    				if(buyerAckNo == lastAckNo) {countAck += 1;}
    				else {countAck = 0;}
    			}
    			if(dataLength > 0) {
    				ackNeededFlag = true; //merge ACK and DATA packets
    				outTraffic.write(packet, IPOffset+TCPOffset, dataLength);
    				
    				//DatagramPacket packetDATAACK = createPKT(Config.PKT_TYPE_DATAACK);
	    			//udpPacketsList.add(packetDATAACK);
    			}
    		} else if(FINFlag>0 && state==Config.TCP_STATE_ACTIVE) {
    			// FIN received, FIN+ACK needed
				ackNo = buyerSequenceNo + 1;
				DatagramPacket packetFINACK = createPKT(Config.PKT_TYPE_FINACK);
    			udpPacketsList.add(packetFINACK); //control packets, goes into udp packets list
    			//TODO: close socket anyway after a period of time, even if buyer's ACK is lost
    		} else if(ACKFlag>0 && state==Config.TCP_STATE_FIN) {
    			// ACK of FIN received, close the socket
    			mSocket.close();
    		} else {
    			Log.e(sellerTAG, "Seller TCP received un-categoried packets!");
    		}
    	}
    	
    	public DatagramPacket createPKT(int type) {
    		byte[] packetByte;
    		DatagramPacket packet = null;
    		if(type == Config.PKT_TYPE_SYNACK) {
    			packetByte = new byte[40];
    			byte[] ipHeader = createIPHeader((short)40, false, (short)0);
    			byte[] tcpHeader = createTCPHeader(type);
    			System.arraycopy(ipHeader, 0, packetByte, 0, 20);
    			System.arraycopy(tcpHeader, 0, packetByte, 20, 20);
    			packet = new DatagramPacket(packetByte, 40);
    		} else if(type == Config.PKT_TYPE_FINACK) {
    			packetByte = new byte[40];
    			byte[] ipHeader = createIPHeader((short)40, false, (short)0);
    			byte[] tcpHeader = createTCPHeader(type);
    			System.arraycopy(ipHeader, 0, packetByte, 0, 20);
    			System.arraycopy(tcpHeader, 0, packetByte, 20, 20);
    			packet = new DatagramPacket(packetByte, 40);
    		}
    		
    		return packet;
    	}
    	
    	public byte[] createIPHeader(short totalLength, boolean M, short fragmentOffset) {
    		byte[] packet = new byte[20];
    		
    		byte headerByte;
			//version + Header Length, assume 20-byte IP/UDP header
			packet[0] = (byte) 84; //0x00101010
			
			//TODO: Type of Service
			packet[1] = (byte) 0;
			
			//Total Length
			headerByte = (byte) (totalLength & 0xff); packet[2] = headerByte;
			headerByte = (byte) ((totalLength >> 8) & 0xff); packet[3] = headerByte;
			
			//TODO: Identification, as in the packet from Buyer
			packet[4] = packet[5] = 0;
			
			//TODO: how to get the IP Flags, and Fragment Offset
			if(M) {headerByte = (byte) 4;}
			else {headerByte = (byte) 0;} //We do not set "Do not Fragment" Flag
			byte tmpWeight = (byte) 8; int tmpOffset = fragmentOffset;
			for(int tmp=1;tmp<6;++tmp) {
				if(tmpOffset%2 > 0) {
					headerByte = (byte) (headerByte + tmpWeight);
				}
				tmpOffset = tmpOffset / 2;
				tmpWeight *= 2;
			}
			packet[6] = headerByte;
			headerByte = (byte) 0; tmpWeight = (byte) 1;
			for(int tmp=0;tmp<8;++tmp) {
				if(tmpOffset%2 > 0) {
					headerByte = (byte) (headerByte + tmpWeight);
				}
				tmpOffset = tmpOffset / 2;
				tmpWeight *= 2;
			}
			packet[7] = headerByte;
			
			//Time To Live 
			packet[8] = (byte) 4;
			
			//Protocol
			headerByte = (byte) Config.PROTOCOL_TCP; packet[9] = headerByte;
			
			//TODO: Header Checksum
			
			//Source and Destination Address
			int tmpint;
			//internet address as the source address
			String[] tmpAdd = internetAddr.split("\\.");
			tmpint = Integer.parseInt(tmpAdd[0]); headerByte = (byte) (tmpint); packet[12] = headerByte;
			tmpint = Integer.parseInt(tmpAdd[1]); headerByte = (byte) (tmpint); packet[13] = headerByte;
			tmpint = Integer.parseInt(tmpAdd[2]); headerByte = (byte) (tmpint); packet[14] = headerByte;
			tmpint = Integer.parseInt(tmpAdd[3]); headerByte = (byte) (tmpint); packet[15] = headerByte;
			//buyer address as the destination address
			tmpAdd = buyerAddr.split("\\.");
			tmpint = Integer.parseInt(tmpAdd[0]); headerByte = (byte) (tmpint); packet[16] = headerByte;
			tmpint = Integer.parseInt(tmpAdd[1]); headerByte = (byte) (tmpint); packet[17] = headerByte;
			tmpint = Integer.parseInt(tmpAdd[2]); headerByte = (byte) (tmpint); packet[18] = headerByte;
			tmpint = Integer.parseInt(tmpAdd[3]); headerByte = (byte) (tmpint); packet[19] = headerByte;
    		
    		return packet;
    	}
    	
    	public byte[] createTCPHeader(int pktType) {
    		byte headerByte;
    		byte[] packet = new byte[20];
			
			//Source and Destination Port
			headerByte = (byte) (internetPort & 0xff); packet[0] = headerByte;
			headerByte = (byte) ((internetPort >> 8) & 0xff); packet[1] = headerByte;
			headerByte = (byte) (buyerPort & 0xff); packet[2] = headerByte;
			headerByte = (byte) ((buyerPort >> 8) & 0xff); packet[3] = headerByte;
			
			//Sequence number
			headerByte = (byte) (seqNoCumulative & 0xff); packet[4] = headerByte;
			headerByte = (byte) ((seqNoCumulative>>8) & 0xff); packet[5] = headerByte;
			headerByte = (byte) ((seqNoCumulative>>16) & 0xff); packet[6] = headerByte;
			headerByte = (byte) ((seqNoCumulative>>24) & 0xff); packet[7] = headerByte;
			//Acknowledge number
			headerByte = (byte) (ackNo & 0xff); packet[8] = headerByte;
			headerByte = (byte) ((ackNo>>8) & 0xff); packet[9] = headerByte;
			headerByte = (byte) ((ackNo>>16) & 0xff); packet[10] = headerByte;
			headerByte = (byte) ((ackNo>>24) & 0xff); packet[11] = headerByte;
			
			//Offset + Reserved
			//TODO: further consider timestamp
			packet[12] = (byte) 5;
			
			//TCP Flags: 
			if(pktType == Config.PKT_TYPE_SYNACK) {
				packet[13] = (byte) 72; //SYN + ACK
			} else if(pktType == Config.PKT_TYPE_DATAACK) {
				packet[13] = (byte) 64; //ACK only
			} else if(pktType == Config.PKT_TYPE_FINACK) {
				packet[13] = (byte) 136; //FIN + ACK at the same time
			} else {
				packet[13] = (byte) 0;
			}
			
			//Window size
			//TODO: check the value of window size
			headerByte = (byte) (sellerFlowControlWindow & 0xff); packet[14] = headerByte;
			headerByte = (byte) ((sellerFlowControlWindow >> 8) & 0xff); packet[15] = headerByte;
			
			//TODO: Checksum
			
			//Urgent Pointer
			packet[18] = packet[19] = 0;
			
			return packet;
    	}
    	
    	public void run() {
    		int length = 0;
			while(true) {
				int dataLength = 0;
				byte[] dataByte = new byte[0];
				byte[] dataByte1 = new byte[Config.DEFAULT_MTU];
				byte[] dataByte2 = new byte[0];
				try {
					length = inTraffic.read(dataByte1, 0, Config.DEFAULT_MTU);
				} catch (IOException e) {
					Log.e(sellerTAG, "Seller failed to read from TCP connection: " + e.toString());
				}
				while(length != -1) {//read the whole segment at one time
					dataByte2 = new byte[length+dataLength];
					System.arraycopy(dataByte, 0, dataByte2, 0, dataLength);
					System.arraycopy(dataByte1, 0, dataByte2, dataLength, length);
					dataLength += length;
					dataByte = new byte[dataLength];
					System.arraycopy(dataByte2, 0, dataByte, 0, dataLength);
					dataByte2 = new byte[0];
					
					try {
						length = inTraffic.read(dataByte1, 0, Config.DEFAULT_MTU);
					} catch (IOException e) {
						Log.e(sellerTAG, "Seller failed to read from TCP connection: " + e.toString());
					}
					//Q: will blocking operation influence the result? -tmeng6
				}
				if(dataLength == 0) {
					try {
						Thread.sleep(Config.DEFAULT_POLL_MS);
					} catch (InterruptedException e) {
						Log.e(sellerTAG, "Seller TCP thread sleep failed: " + e.toString());
					}
				} else {
					byte[] TCPHeader = new byte[20];
					if(ackNeededFlag) {
						TCPHeader = createTCPHeader(Config.PKT_TYPE_DATAACK);
						ackNeededFlag = false;
					} else {
						TCPHeader = createTCPHeader(Config.PKT_TYPE_DATA);
					}
					dataByte2 = new byte[dataLength+20];
					System.arraycopy(dataByte, 0, dataByte2, 0, dataLength);
					System.arraycopy(TCPHeader, 0, dataByte2, dataLength, 20);
					byteBufferList.add(dataByte2);
					byteOffsetList.add(0);
				}
    		}
    	}
    }
    
    private void relayUDPIncoming(byte[] packetByte, int length) {
    	ByteBuffer packetBuffer = ByteBuffer.allocate(Config.DEFAULT_MTU);
    	packetBuffer = ByteBuffer.wrap(packetByte);
    	
    	//processing NAT function, get the source/destination IP address,
		String sourceAddress = (packetBuffer.get(12) & 0xFF) + "." +
							   (packetBuffer.get(13) & 0xFF) + "." +
							   (packetBuffer.get(14) & 0xFF) + "." +
							   (packetBuffer.get(15) & 0xFF);
		String destAddress   = (packetBuffer.get(16) & 0xFF) + "." +
							   (packetBuffer.get(17) & 0xFF) + "." +
							   (packetBuffer.get(18) & 0xFF) + "." +
							   (packetBuffer.get(19) & 0xFF);
		int headerLength = (packetBuffer.get(0) & 0xf) * 4;
		int sourcePort = packetBuffer.getShort(headerLength) & 0xffff;
		int destPort = packetBuffer.getShort(headerLength+2) & 0xffff;
		//short identification = (short) (packetBuffer.get(4) + packetBuffer.get(5)*256);
		packetBuffer.clear();
		
		Log.d(sellerTAG, "UDP-"+length+"/"+headerLength+"-"+sourceAddress+"/"+sourcePort+"-"+destAddress+"/"+destPort);
		Message msg = new Message();
        Bundle b = new Bundle();
        b.putString("message", "countPoll="+countPoll+"-receiveUDP-"+length+"-"+sourceAddress+"/"+sourcePort+"-"+destAddress+"/"+destPort);
        msg.setData(b);
        mainHandler.sendMessage(msg);
		
		DatagramPacket data = null;
        String buyerAddress = sourceAddress + sourcePort;
		try {
			data = new DatagramPacket(packetByte, headerLength+8, length-headerLength-8,
										(new InetSocketAddress(destAddress, destPort)));
			
			if(UDPsocketMap.containsKey(buyerAddress)) {
				Log.i(sellerTAG, "already contained UDP sellerSocket");
				UDPsocketMap.get(buyerAddress).SendPacket(data);
			} else {
				Log.i(sellerTAG, "new UDP sellerSocket");
				SellerUDPSocket newSocket = new SellerUDPSocket(sourceAddress, sourcePort);
				UDPsocketMap.put(buyerAddress, newSocket);
				// first prepare to listen for incoming packets before sending
				newSocket.start();
				// listening is ready, then we send the packet
				newSocket.SendPacket(data);
			}
		} catch (SocketException e) {
			Log.e(sellerTAG, "Seller create DatagramPacket failed: " + e.toString());
		} catch (IOException e1) {
			Log.e(sellerTAG, "Seller create SellerUDPSocket failed: " + e1.toString());
		}
    }
    
    public class SellerUDPSocket extends Thread {
		private DatagramSocket sellerUDPSocket = null;
		private String buyerAddr;
		private int buyerPort;
		private int socketIdentification;
    	
    	public SellerUDPSocket(String add, int port) throws IOException {
    		buyerAddr = add; buyerPort = port;
    		socketIdentification = new Random().nextInt(40000);
    		DatagramChannel sellerChannel = DatagramChannel.open();
			sellerUDPSocket = sellerChannel.socket();
			// use blocking channel to avoid consuming computing resources
			sellerChannel.configureBlocking(true);
			
			//int randomPort = new Random().nextInt(40000); randomPort += 10000;
			//SocketAddress mTunnelAddress = new InetSocketAddress(nextFreePort);
	        sellerUDPSocket.bind(null);
    	}
    	
    	public void SendPacket(DatagramPacket packet) {
    		try {
    			Log.i(sellerTAG, "Thread("+buyerAddr+"/"+buyerPort+") send - "+packet.getLength());
				sellerUDPSocket.send(packet);
			} catch (IOException e) {
				Log.e(sellerTAG, "Thread("+buyerAddr+"/"+buyerPort+") send failed: " + e.toString());
			}
    	}
    	
    	public void run() {
    		//Log.i(sellerTAG, "Thread("+buyerAddr+"/"+buyerPort+") begins listening ...");
    		boolean timeoutFlag = false;
			int length;
			while(true) {
				timeoutFlag = false;
    			
				byte[] dataToBackByte = new byte[Config.DEFAULT_MTU-28]; //minus 28 for IP and UDP header
				DatagramPacket dataToBack = new DatagramPacket(dataToBackByte, dataToBackByte.length);
				length = 0;
				try {
					sellerUDPSocket.receive(dataToBack);
				} catch (SocketTimeoutException e) {
					Log.i(sellerTAG, "Thread("+buyerAddr+"/"+buyerPort+") recv TIMEOUT");
					timeoutFlag = true;
				} catch (IOException e) {
					Log.e(sellerTAG, "Seller receive failed: " + e.toString());
				}
				
				if(timeoutFlag) {
					//break;
				} else {
					length = dataToBack.getLength();
					if(length <= 0) {continue;}
					
					length = dataToBack.getLength();
					String internetAddr = dataToBack.getAddress().getHostAddress();
					short internetPort = (short) dataToBack.getPort();
					int internetPortint = internetPort & 0xffff;
					Log.i(sellerTAG, "Thread("+buyerAddr+"/"+buyerPort+") recv("+length+")-"+internetAddr+"/"+internetPortint);
					
					ByteBuffer packetToBackBuffer = ByteBuffer.allocate(Config.DEFAULT_MTU);
					packetToBackBuffer = ByteBuffer.wrap(dataToBackByte, 28, length);
					
					//version + header length, assume 20-byte IP header
					packetToBackBuffer.put(0, (byte) 69);
					//Type of Service, zeros according to wireshark packets
					packetToBackBuffer.put(1, (byte) 0);
					//Total Length
					packetToBackBuffer.putShort(2, (short) (28+length));
					//Identification
					packetToBackBuffer.putShort(4, (short) socketIdentification); socketIdentification += 1;
					//IP Flags and Fragment Offset, assume all zero
					packetToBackBuffer.putShort(6, (short) 0);
					//Time to live, assume to be 64
					packetToBackBuffer.put(8, (byte) 64);
					//Protocol, with UDP = 17
					packetToBackBuffer.put(9, (byte) Config.PROTOCOL_UDP);
					//TODO: Header Checksum
					//...
					//Source and Destination Address
					int tmpint;
					String[] tmpAdd = internetAddr.split("\\.");
					tmpint = Integer.parseInt(tmpAdd[0]); packetToBackBuffer.put(12, (byte) tmpint);
					tmpint = Integer.parseInt(tmpAdd[1]); packetToBackBuffer.put(13, (byte) tmpint);
					tmpint = Integer.parseInt(tmpAdd[2]); packetToBackBuffer.put(14, (byte) tmpint);
					tmpint = Integer.parseInt(tmpAdd[3]); packetToBackBuffer.put(15, (byte) tmpint);
					tmpAdd = buyerAddr.split("\\.");
					tmpint = Integer.parseInt(tmpAdd[0]); packetToBackBuffer.put(16, (byte) tmpint);
					tmpint = Integer.parseInt(tmpAdd[1]); packetToBackBuffer.put(17, (byte) tmpint);
					tmpint = Integer.parseInt(tmpAdd[2]); packetToBackBuffer.put(18, (byte) tmpint);
					tmpint = Integer.parseInt(tmpAdd[3]); packetToBackBuffer.put(19, (byte) tmpint);
					//Source and Destination Port
					packetToBackBuffer.putShort(20, internetPort);
					packetToBackBuffer.putShort(22, (short) buyerPort);
					//UDP Datagram Length
					packetToBackBuffer.putShort(24, (short) (8+length));
					//TODO: Checksum
					//...
					
					DatagramPacket packetToBack = null;
					try {
						packetToBack = new DatagramPacket(packetToBackBuffer.array(), 0, length+28,
																(new InetSocketAddress("192.168.49.211", Config.BUYER_CLIENT_PORT)));
					} catch (SocketException e) {
						Log.e(sellerTAG, "Thread("+buyerAddr+"/"+buyerPort+") pack pkt failed: " + e.toString());
					}
					udpPacketsList.add(packetToBack);
				}
    		}
			
//			sellerUDPSocket.close();
			//TODO: refine when to close the socket
			//TODO: close the thread to avoid seller use a closed sellerUDPSocket
			//when the run() function returns, the thread end? -tmeng6
    	}
	}
    
    @Override
    public boolean handleMessage(Message message) {
    	if (message != null) {
    		Bundle b = message.getData();
    		String msg = b.getString("message");
    		Toast.makeText(this, msg, Toast.LENGTH_SHORT).show();
        }
        return true;
    }
}
