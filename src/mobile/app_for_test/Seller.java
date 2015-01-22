package mobile.app_for_test;

import java.io.FileOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.DatagramPacket;
import java.net.DatagramSocket;
import java.net.InetSocketAddress;
import java.net.Socket;
import java.net.SocketAddress;
import java.net.SocketException;
import java.net.SocketTimeoutException;
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
	private HandlerThread incomingThread;
    private Handler incomingHandler;
    private HandlerThread outgoingThread;
    private Handler outgoingHandler;
    
    private DatagramSocket mSocket;
    private static int mPort = Config.DEFAULT_PORT_NUMBER;
    
    //TODO: replace with listview
//	private TextView textview = null;
	
	private Map<String, SellerUDPSocket> UDPsocketMap;
	private List<DatagramPacket> udpPacketsList; //TCP control packets also goes in here
	
	private Map<String, SellerTCPSocket> TCPsocketMap;
	private short sellerFlowControlWindow;
	private List<String> TCPremoveList; //for removing TCP socket from socket map
	
	private boolean isConnected = false;
	
	@Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_seller);
        
//        textview = (TextView) findViewById(R.id.text_ip_seller);
        UDPsocketMap = Collections.synchronizedMap(new HashMap<String, SellerUDPSocket>());
        udpPacketsList = Collections.synchronizedList(new ArrayList<DatagramPacket>());
        
        TCPsocketMap = Collections.synchronizedMap(new HashMap<String, SellerTCPSocket>());
        sellerFlowControlWindow = 14480;
        TCPremoveList = new ArrayList<String>();
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
				if(incomingThread != null) {
		            incomingThread.quit();
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
    	
    	if(incomingThread != null) {
            Log.d(sellerTAG, "Stopping previous incoming thread");
            incomingThread.interrupt();
        }
    	if(outgoingThread != null) {
    		Log.d(sellerTAG, "Stopping previous outgoing thread");
    		outgoingThread.interrupt();
    	}
    	if(isConnected) {
    		isConnected = false;
    		mSocket.close();
    	}
    	
    	incomingThread = new HandlerThread("SellerIncomingThread");
        incomingThread.start();
        incomingHandler = new Handler(incomingThread.getLooper());
        incomingHandler.post(waitForVpnConnection);
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
			
            incomingHandler.post(relayIncoming);
            
            // Use separate threads for incoming and outgoing
            outgoingThread = new HandlerThread("SellerOutgoingThread");
            outgoingThread.start();
            outgoingHandler = new Handler(outgoingThread.getLooper());
            outgoingHandler.post(relayOutgoing);

        }
    };
    
    Runnable relayOutgoing = new Runnable() {
    	@Override
    	public void run() {
    		relayTCPOutgoing();
    		relayUDPOutgoing();
    		
    		outgoingHandler.post(relayOutgoing);
    	}
    };
    
    private boolean relayTCPOutgoing() {
    	if(TCPsocketMap.size() == 0) {return false;} //no active TCP connections
    	
    	Set<Entry<String,SellerTCPSocket>> entries = TCPsocketMap.entrySet();
    	//Set<String> setOfMap = TCPsocketMap.keySet();
    	synchronized(TCPsocketMap) {
    		Iterator<Entry<String,SellerTCPSocket>> itr = entries.iterator(); // Must be in synchronized block
    		while (itr.hasNext()) {
    			Entry<String,SellerTCPSocket> en = itr.next();
    			
    			if( (en.getValue().stopWaitTime == -20 ) ||
    					(en.getValue().stopWaitTime > 0 && System.currentTimeMillis()-en.getValue().stopWaitTime>Config.DEFAULT_TCP_TIMEOUT) ) {
    				TCPremoveList.add(en.getKey());
    			} else {
    				en.getValue().sendPackets();
    			}
			}
    		
    		for(int i=0;i<TCPremoveList.size();++i) {TCPsocketMap.remove(TCPremoveList.get(i));}
    		TCPremoveList.clear();
    	}
    	
    	return true;
    }
    
    private boolean relayUDPOutgoing() {
    	if(udpPacketsList.size() == 0) {return false;}
    	
    	//use synchronized list like this?
    	synchronized(udpPacketsList) {
    		Iterator<DatagramPacket> itr = udpPacketsList.iterator(); // Must be in synchronized block
    		while (itr.hasNext()) {
    			DatagramPacket newpacket = itr.next();
    			//Log.d(sellerTAG, "Sending UDP: "+newpacket.getLength());
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
    		boolean packetProcessed = false;
	    	
	    	try {
	    		byte[] packetByte = new byte[Config.DEFAULT_MTU];
	    		DatagramPacket packet = null;
	    		while(true) {
	    			packet = new DatagramPacket(packetByte, packetByte.length);
	    			mSocket.receive(packet);
	    			if(packet.getLength() <= 0) {break;}
	    			
	    			int protocol = packetByte[Config.PROTOCOL_OFFSET];
	    			if(protocol == Config.PROTOCOL_TCP) {
	    				// do not consider fragmentation for packets from Buyer, which is quite rare
	    				relayTCPIncoming(packetByte, packet.getLength());
	    			} else if(protocol == Config.PROTOCOL_UDP) {
	    				relayUDPIncoming(packetByte, packet.getLength());
	    			} else {
	    				Log.i(sellerTAG, "Dropping unsupported protocol: " + protocol + ", length: " + packet.getLength());
	    				continue;
	    			}
	    			
	    			packetProcessed = true;
	    			packetByte = new byte[Config.DEFAULT_MTU];
	    		}
			} catch (SocketTimeoutException e) {
			} catch (IOException e) {
				Log.e(sellerTAG, "Receive from buyer failed: " + e.toString());
			}
	    	
	    	if(packetProcessed) {
        		incomingHandler.post(relayIncoming);
        	} else {
        		incomingHandler.postDelayed(relayIncoming, Config.DEFAULT_POLL_MS);
        	}
    	}
    };
    
    private void relayTCPIncoming(byte[] packetByte, int length) {
    	ByteBuffer packetBuffer = ByteBuffer.allocate(Config.DEFAULT_MTU);
    	packetBuffer = ByteBuffer.wrap(packetByte);
    	
    	String sourceAddress = (packetByte[12] & 0xFF) + "." +
    				(packetByte[13] & 0xFF) + "." +
    				(packetByte[14] & 0xFF) + "." +
    				(packetByte[15] & 0xFF);
    	String destAddress = (packetByte[16] & 0xFF) + "." +
    				(packetByte[17] & 0xFF) + "." +
    				(packetByte[18] & 0xFF) + "." +
    				(packetByte[19] & 0xFF);
    	int headerLength = (packetByte[0] & 0xf) * 4;
    	int sourcePort = packetBuffer.getShort(headerLength) & 0xffff;
    	int destPort = packetBuffer.getShort(headerLength+2) & 0xffff;
    	
		int offset = ( (packetByte[headerLength+12]>>4) & 0xf) * 4;
		int flags = packetByte[headerLength+13]&0xff;
		
		packetBuffer.clear();
		
		String tcpAddress = sourceAddress + sourcePort + destAddress + destPort;
		if(TCPsocketMap.containsKey(tcpAddress)) {
			try {
				TCPsocketMap.get(tcpAddress).handlePacket(packetByte, headerLength, offset, length, flags);
			} catch (IOException e) {
				Log.e(sellerTAG, "TCP Thread (" +sourceAddress+"/"+sourcePort+"-"+destAddress+"/"+destPort+") (existed) relay incoming failed: " + e.toString());
			}
		} else
		{
			int SYNFlag = flags & 0x02;
			int FINFlag = flags & 0x01;
			int ACKFlag = flags & 0x10;
			int RSTFlag = flags & 0x04;
			
			if(SYNFlag>0 && ACKFlag<=0) {
				// build the TCP socket
				SellerTCPSocket newSocket = null;
				try {
					newSocket = new SellerTCPSocket(sourceAddress, sourcePort, destAddress, destPort);
					newSocket.start();
					newSocket.handlePacket(packetByte, headerLength, offset, length, flags);
					synchronized(TCPsocketMap) {
						TCPsocketMap.put(tcpAddress, newSocket);
					}
				} catch (IOException e) {
					Log.e(sellerTAG, "TCP Thread (" +sourceAddress+"/"+sourcePort+"-"+destAddress+"/"+destPort+") (new) relay incoming failed: " + e.toString());
				}
				
			} else {
				// impossible/ERROR: request for an in-exist TCP connection
				Log.e(sellerTAG, "TCP Thread (" +sourceAddress+"/"+sourcePort+"-"+destAddress+"/"+destPort+") illegle request for in-exist TCP ("+SYNFlag+"|"+ACKFlag+"|"+FINFlag+"|"+RSTFlag+")");
			}
		}
    }
    
    public class SellerTCPSocket extends Thread {
    	private String buyerAddr;
    	private int buyerPort;
    	private String internetAddr;
    	private int internetPort;
    	
    	private Socket sellerTCPSocket = null;
    	private OutputStream outTraffic;
    	private InputStream inTraffic;
    	
    	private int state;
    	private boolean stopState;
    	private long stopWaitTime; //FIXME: public or private
    	
    	private List<byte[]> tcpByteList;
    	private List<Integer> tcpSeqNoList;
    	private List<Long> tcpTimestampsList;
    	private int consumedWindow;
    	
    	private int socketIdentification;
    	private int seqNoCumulative; // current seq no.
    	private int seqNoAcked; // the most recently acked seq no.
    	private int ackNo; // the next seq no. wanna hear from buyer
    	private int lastAckNo;
    	private int countDupAck;
    	
    	private int buyerFlowControlWindow;
    	private int buyerFlowControlWindowScale;
    	
    	private int maxSegmentSize;
    	
//    	private int lastTimestamp;
//    	private int myTimestamp;
//    	private long myTime;
    	
    	public SellerTCPSocket(String srcAdd, int srcPort, String dstAdd, int dstPort) throws IOException {
    		buyerAddr = srcAdd; buyerPort = srcPort;
    		internetAddr = dstAdd; internetPort = dstPort;
    		socketIdentification = new Random().nextInt(10000);
    		seqNoCumulative = new Random().nextInt(100); //randomly generate sequence number
    		seqNoAcked = seqNoCumulative;
    		ackNo = 0;
    		consumedWindow = 0;
    		state = Config.TCP_STATE_SYN;
    		stopState = false;
    		stopWaitTime = -1;
    		if(sellerTCPSocket == null) {
    			sellerTCPSocket = new Socket(internetAddr, internetPort);
    		}
    		outTraffic = sellerTCPSocket.getOutputStream();
    		inTraffic = sellerTCPSocket.getInputStream();
    		
    		tcpByteList = Collections.synchronizedList(new ArrayList<byte[]>());
    		tcpSeqNoList = Collections.synchronizedList(new ArrayList<Integer>());
    		tcpTimestampsList = Collections.synchronizedList(new ArrayList<Long>());
    		
    		countDupAck = lastAckNo = -1;
    		
    		maxSegmentSize = Config.DEFAULT_MSS;
    	}
    	
    	public void sendPackets() {
    		if(tcpByteList.size() == 0) {return;}
    		synchronized(tcpByteList) {
    			for(int i=0;i<tcpByteList.size();++i) {
	    			int length = tcpByteList.get(i).length;
	    			
	    			// remove ACK-ed packets
	    			if(seqNoAcked > tcpSeqNoList.get(i)) {
	    				consumedWindow -= length;
	    				tcpByteList.remove(i); tcpTimestampsList.remove(i); tcpSeqNoList.remove(i);
	    				i -= 1;
	    				continue;
	    			}
	    			
	    			long currentTime = System.currentTimeMillis();
//	        		myTimestamp = myTimestamp + (int)(currentTime-myTime); myTime = currentTime;
	        		if( (tcpTimestampsList.get(i) == (long)-1 && consumedWindow+length <= buyerFlowControlWindow*buyerFlowControlWindowScale) ||
	        				(tcpTimestampsList.get(i) != (long)-1 && currentTime-tcpTimestampsList.get(i) > Config.TCP_LOST_TIMEOUT) ||
	        				(tcpSeqNoList.get(i) == lastAckNo && countDupAck >= 3) ) {
	        			consumedWindow = (tcpTimestampsList.get(i) == (long) -1)? (consumedWindow+length) : consumedWindow;
	        			
	    				ByteBuffer packetToBackBuffer = ByteBuffer.wrap(tcpByteList.get(i));
	    				// Checksum: initialize to zero
	    				packetToBackBuffer.putShort(36, (short)0);
	    				// TimeStamp: 8 10 XXXX XXXX
//	    				packetToBackBuffer.putInt(44, myTimestamp); packetToBackBuffer.putInt(48, lastTimestamp);
	    				tcpTimestampsList.set(i, currentTime);
	    				
						byte[] pseudoHeader = new byte[length-8]; //12+32 -> (20+32 - 8)
		    			ByteBuffer pseudoBuffer = ByteBuffer.wrap(pseudoHeader);
		    			for(int j=0;j<8;++j) {pseudoHeader[j] = packetToBackBuffer.get(j+12);} // SRC/DST Address
		    			pseudoHeader[8] = (byte) 0; pseudoHeader[9] = (byte) Config.PROTOCOL_TCP;
		    			pseudoBuffer.putShort(10, (short)(length-20));
		    			System.arraycopy(tcpByteList.get(i), 20, pseudoHeader, 12, length-20);
		    			packetToBackBuffer.putChar(36, (char) HelperFunc.calcChecksum(pseudoHeader));
		    			
		    			DatagramPacket packetToBack = null;
						try {
							//Log.d(sellerTAG, "TCP Thread("+buyerAddr+"/"+buyerPort+"-"+internetAddr+"/"+internetPort+") send TCP: " + length+", seq="+tcpSeqNoList.get(i));
							//TODO: use buyerAddr instead of "192.168.49.206"
							packetToBack = new DatagramPacket(tcpByteList.get(i), 0, length,
														(new InetSocketAddress("192.168.49.206", Config.BUYER_CLIENT_PORT)));
							mSocket.send(packetToBack);
						} catch (SocketException e) {
							Log.e(sellerTAG, "TCP Thread("+buyerAddr+"/"+buyerPort+"-"+internetAddr+"/"+internetPort+") create TCP pkt failed: " + e.toString());
						} catch (IOException e) {
							Log.e(sellerTAG, "TCP Thread("+buyerAddr+"/"+buyerPort+"-"+internetAddr+"/"+internetPort+") send TCP pkt failed: " + e.toString());
						}
	    			}
	    		}
    		}
    	}
    	
    	// handle incoming packet from Buyer
    	public void handlePacket(byte[] packet, int IPOffset, int TCPOffset, int length, int flags) throws IOException {
    		int SYNFlag = flags & 0x02;
    		int FINFlag = flags & 0x01;
    		int ACKFlag = flags & 0x10;
    		int RSTFlag = flags & 0x04;
    		
    		if(RSTFlag > 0) {
    			this.interrupt();
    			sellerTCPSocket.shutdownInput();
    			sellerTCPSocket.shutdownOutput();
    			sellerTCPSocket.close();
    			synchronized(tcpByteList) {
    				tcpByteList.clear();
    				tcpSeqNoList.clear();
    				tcpTimestampsList.clear();
    			}
    			stopWaitTime = -20;
    			
    			Log.d(sellerTAG, "TCP Thread (" +buyerAddr+"/"+buyerPort+"-"+internetAddr+"/"+internetPort+") Receive RST");
    			return;
			}
    		
    		ByteBuffer packetBuffer = ByteBuffer.wrap(packet);
    		
    		int buyerSequenceNo = packetBuffer.getInt(IPOffset+4);
    		int buyerAckNo = packetBuffer.getInt(IPOffset+8);
    		buyerFlowControlWindow = packetBuffer.getShort(IPOffset+14) & 0xffff;
    		for(int i=IPOffset+20;i<IPOffset+TCPOffset;++i) {
    			int optionKind = packetBuffer.get(i) & 0xff;
    			if(optionKind <= 1) {}
    			else if(optionKind == 2) {
    				//maxSegmentSize = packetBuffer.getShort(i+2) & 0xffff;
    				//maxSegmentSize = Math.min(maxSegmentSize, 1400);
    				i += 3;
    			} else if(optionKind == 3) {
    				buyerFlowControlWindowScale = packetBuffer.get(i+2) & 0xff;
    				i += 2;
    			} else if(optionKind == 4) {
    				i += 1;
				} else if(optionKind == 8) {
//					lastTimestamp = packetBuffer.getInt(i+2);
					i += 9;
				} else if(optionKind == 5) {
					//TODO: Selective ACK
				}
    		}
    		
    		if(SYNFlag>0 && ACKFlag<=0 && (state==Config.TCP_STATE_SYN || state==Config.TCP_STATE_FIN)) {
    			// SYN received, SYNACK needed
    			//Log.d(sellerTAG, "TCP Thread (" +buyerAddr+"/"+buyerPort+"-"+internetAddr+"/"+internetPort+") Receive SYN ("+length+"): Seq=" + buyerSequenceNo + "/MSS=" + maxSegmentSize);
    			state = Config.TCP_STATE_SYN;
    			
    			ackNo = buyerSequenceNo + 1;
    			DatagramPacket packetSYNACK = createPKT(Config.PKT_TYPE_SYNACK, TCPOffset);
    			//udpPacketsList.add(packetSYNACK); //control packets, goes into udp packets list
    			mSocket.send(packetSYNACK); //send directly ...
    			//Log.d(sellerTAG, "TCP Thread (" +buyerAddr+"/"+buyerPort+"-"+internetAddr+"/"+internetPort+") Send SYNACK ("+length+"): Seq="+(seqNoCumulative-1)+"/Ack="+ackNo);
    		} else if(SYNFlag<=0 && ACKFlag>0 && state==Config.TCP_STATE_SYN) {
    			// ACK for SYNACK received, TCP connection established
    			//Log.d(sellerTAG, "TCP Thread (" +buyerAddr+"/"+buyerPort+"-"+internetAddr+"/"+internetPort+") Receive ACK of SYN ("+length+"): Seq=" + buyerSequenceNo + "/Ack=" + buyerAckNo);
    			if(buyerAckNo == seqNoAcked+1) {
    				seqNoAcked += 1;
    				state = Config.TCP_STATE_ACTIVE;
    			}
    		} else if(SYNFlag<=0 && FINFlag<=0 && (state==Config.TCP_STATE_ACTIVE || state==Config.TCP_STATE_SYN)) {
    			//TCP state SYN means the ACK from buyer during handshake is lost
    			//Log.d(sellerTAG, "TCP Thread (" +buyerAddr+"/"+buyerPort+"-"+internetAddr+"/"+internetPort+") Receive TCP PKT ("+length+"): Seq="+buyerSequenceNo+"/Ack=" + buyerAckNo);
    			int dataLength = length - IPOffset - TCPOffset;
    			
    			if(buyerSequenceNo == ackNo) {ackNo += dataLength;}
    			else {
    				//TODO: selective ACK
    			}
    			
    			if(buyerAckNo > seqNoAcked) {seqNoAcked = buyerAckNo;}
    			//TODO: selective ACK
    			
    			if(stopState == true) {
    				if(seqNoAcked == seqNoCumulative) {
    					state = Config.TCP_STATE_FIN;
    				} else {
    					//FIN+ACK is lost
    					seqNoCumulative -= 1;
    					DatagramPacket packetFINACK = createPKT(Config.PKT_TYPE_FINACK, 32);
        				mSocket.send(packetFINACK);
    				}
    			} else if(dataLength > 0) {
    				//ackNeededFlag = true; //merge ACK and DATA packets
    				outTraffic.write(packet, IPOffset+TCPOffset, dataLength);
    				
    				DatagramPacket packetDATAACK = createPKT(Config.PKT_TYPE_DATAACK, 32);
	    			//udpPacketsList.add(packetDATAACK);
	    			mSocket.send(packetDATAACK);
    			} else {
    				//handle possible duplicate ACK
    				if(buyerAckNo == lastAckNo) {countDupAck += 1;}
    				else {lastAckNo = buyerAckNo; countDupAck = 0;}
    			}
    		} else if(FINFlag>0) {
    			ackNo = buyerSequenceNo + 1;
    			if(stopState == false) {
	    			// FIN received, FIN+ACK needed
	    			//Log.d(sellerTAG, "TCP Thread (" +buyerAddr+"/"+buyerPort+"-"+internetAddr+"/"+internetPort+") Receive TCP FINACK ("+length+"): Seq=" + buyerSequenceNo+"/Ack="+buyerAckNo);
					DatagramPacket packetFINACK = createPKT(Config.PKT_TYPE_FINACK, 32);
	    			//udpPacketsList.add(packetFINACK);
	    			mSocket.send(packetFINACK);
    			} else {
    				DatagramPacket packetACK = createPKT(Config.PKT_TYPE_DATAACK, 32);
    				mSocket.send(packetACK);
    				stopWaitTime = System.currentTimeMillis();
    			}
    			state = Config.TCP_STATE_FIN;
    			//TODO: close socket anyway after a period of time, even if buyer's ACK is lost
    		} else if(ACKFlag>0 && (length-IPOffset-TCPOffset)==0 && state==Config.TCP_STATE_FIN) {
    			//TODO: remove this socket thread
    			stopWaitTime = -20;
    			this.interrupt();
    			sellerTCPSocket.shutdownInput();
    			sellerTCPSocket.shutdownOutput();
    			sellerTCPSocket.close();
    			synchronized(tcpByteList) {
    				tcpByteList.clear();
    				tcpSeqNoList.clear();
    				tcpTimestampsList.clear();
    			}
    		} else {
    			Log.e(sellerTAG, "("+buyerAddr+"/"+buyerPort+"-"+internetAddr+"/"+internetPort+"): un-categoried packets|" + SYNFlag+"/"+ACKFlag+"/"+FINFlag);
    		}
    	}
    	
    	public DatagramPacket createPKT(int type, int tcpOffset) throws SocketException {
    		byte[] packetByte; //byte[] ipHeader; byte[] tcpHeader;
    		ByteBuffer packetBuffer;
    		if(type == Config.PKT_TYPE_SYNACK) {
    			packetByte = new byte[20+tcpOffset];
    			createIPHeader(packetByte, 0, (short)(20+tcpOffset), false, (short)0);
    			createTCPHeader(packetByte, 20, type, tcpOffset);
    			packetBuffer = ByteBuffer.wrap(packetByte);
    			
    			seqNoCumulative += 1;
    		} else if(type == Config.PKT_TYPE_DATAACK) {
    			packetByte = new byte[20+tcpOffset];
    			createIPHeader(packetByte, 0, (short)(20+tcpOffset), false, (short)0);
    			createTCPHeader(packetByte, 20, type, tcpOffset);
    			packetBuffer = ByteBuffer.wrap(packetByte);
    		} else if(type == Config.PKT_TYPE_FINACK) {
    			packetByte = new byte[20+tcpOffset];
    			createIPHeader(packetByte, 0, (short)(20+tcpOffset), false, (short)0);
    			createTCPHeader(packetByte, 20, type, tcpOffset);
    			packetBuffer = ByteBuffer.wrap(packetByte);
    			
    			seqNoCumulative += 1;
    		} else {return null;}
    		
    		//checksum computation
			byte[] pseudoHeader = new byte[12+tcpOffset];
			ByteBuffer pseudoBuffer = ByteBuffer.wrap(pseudoHeader);
			for(int i=0;i<8;++i) {pseudoHeader[i] = packetByte[12+i];}
			pseudoHeader[8] = (byte) 0; pseudoHeader[9] = (byte) Config.PROTOCOL_TCP;
			pseudoBuffer.putShort(10, (short) tcpOffset);
			System.arraycopy(packetByte, 20, pseudoHeader, 12, tcpOffset);
			packetBuffer.putChar(36, (char) HelperFunc.calcChecksum(pseudoHeader));
    		DatagramPacket packet = new DatagramPacket(packetByte, 0, 20+tcpOffset,
							(new InetSocketAddress("192.168.49.206", Config.BUYER_CLIENT_PORT)));
    		return packet;
    	}
    	
    	public void createIPHeader(byte[] array, int offset, short totalLength, boolean M, short fragmentOffset) {
    		ByteBuffer packetBuffer = ByteBuffer.wrap(array);
    		
    		//version + Header Length, assume 20-byte IP/UDP header
			packetBuffer.put(offset, (byte) 69);
			//Type of Service
			packetBuffer.put(offset+1, (byte) 0);
			//Total Length
			packetBuffer.putShort(offset+2, totalLength);
			//Identification
			packetBuffer.putShort(offset+4, (short) socketIdentification); socketIdentification += 1;
			//IP Flags and Fragment Offset
			packetBuffer.put(offset+6, (byte) 0);
			packetBuffer.put(offset+7, (byte) 0);
			//Time To Live
			packetBuffer.put(offset+8, (byte) 64);
			//Protocol
			packetBuffer.put(offset+9, (byte) Config.PROTOCOL_TCP);
			//Source and Destination Address
			int tmpint;
			//internet address as the source address
			String[] tmpAdd = internetAddr.split("\\.");
			tmpint = Integer.parseInt(tmpAdd[0]); packetBuffer.put(offset+12, (byte) tmpint);
			tmpint = Integer.parseInt(tmpAdd[1]); packetBuffer.put(offset+13, (byte) tmpint);
			tmpint = Integer.parseInt(tmpAdd[2]); packetBuffer.put(offset+14, (byte) tmpint);
			tmpint = Integer.parseInt(tmpAdd[3]); packetBuffer.put(offset+15, (byte) tmpint);
			//buyer address as the destination address
			tmpAdd = buyerAddr.split("\\.");
			tmpint = Integer.parseInt(tmpAdd[0]); packetBuffer.put(offset+16, (byte) tmpint);
			tmpint = Integer.parseInt(tmpAdd[1]); packetBuffer.put(offset+17, (byte) tmpint);
			tmpint = Integer.parseInt(tmpAdd[2]); packetBuffer.put(offset+18, (byte) tmpint);
			tmpint = Integer.parseInt(tmpAdd[3]); packetBuffer.put(offset+19, (byte) tmpint);
			//IP Header Checksum, first initialize to be zeros
			packetBuffer.putShort(offset+10, (short) 0);
			byte[] ipHeader = new byte[20]; System.arraycopy(array, offset, ipHeader, 0, 20);
			packetBuffer.putChar(offset+10, (char) HelperFunc.calcChecksum(ipHeader));
    	}
    	
    	public void createTCPHeader(byte[] array, int offset, int pktType, int tcpOffset) {
    		ByteBuffer packetBuffer = ByteBuffer.wrap(array);
			
			//Source and Destination Port
    		packetBuffer.putShort(offset, (short) internetPort);
    		packetBuffer.putShort(offset+2, (short) buyerPort);
			//Sequence number
    		packetBuffer.putInt(offset+4, seqNoCumulative);
			//Acknowledge number
			packetBuffer.putInt(offset+8, ackNo);
			// Checksum (to be computed later in createPKT)
			packetBuffer.putShort(offset+16, (short) 0);
			//Urgent Pointer
			packetBuffer.putShort(offset+18, (short) 0);
			// Offset + Reserved
			packetBuffer.put(offset+12, (byte) ((tcpOffset/4)*16));
			
			if(pktType == Config.PKT_TYPE_SYNACK) {
				if(tcpOffset!=40) {Log.e(sellerTAG, "RECEIVED SYN TCP HEADER NOT 20+20");} //FIXME: SYNACK size
				// TCP Flags
				packetBuffer.put(offset+13, (byte) 18);
				//window size
				packetBuffer.putShort(offset+14, sellerFlowControlWindow);
				
				// TCP Options
				// MSS: 2 4 1400
				packetBuffer.put(offset+20, (byte) 2); packetBuffer.put(offset+21, (byte) 4);
				packetBuffer.putShort(offset+22, (short) 1400);
				// SACK: 4 2
//				packetBuffer.put(24, (byte) 4); packetBuffer.put(25, (byte) 2); //TODO: selective ACK
				packetBuffer.put(offset+24, (byte) 1); packetBuffer.put(offset+25, (byte) 1);
				// TimeStamp: 8 10 XXXX XXXX
//				myTime = System.currentTimeMillis();
//				myTimestamp = new Random().nextInt(100000);
//				packetBuffer.put(26, (byte) 8); packetBuffer.put(27, (byte) 10);
//				packetBuffer.putInt(28, myTimestamp); packetBuffer.putInt(32, lastTimestamp);
				for(int pos=26;pos<36;++pos) {packetBuffer.put(offset+pos, (byte) 1);}
				// NOP
				packetBuffer.put(offset+36, (byte) 1);
				// Window Scale
				packetBuffer.put(offset+37, (byte) 3);
				packetBuffer.put(offset+38, (byte) 3);
				packetBuffer.put(offset+39, (byte) 5);
			} else if(pktType == Config.PKT_TYPE_DATAACK) {
				// TCP Flags
				packetBuffer.put(offset+13, (byte) 16);
				// Window Size
				packetBuffer.putShort(offset+14, (short) 486); //486 * (2^5) = 15552
				
				// TCP Options
				// NOP x 2
				packetBuffer.put(offset+20, (byte) 1); packetBuffer.put(offset+21, (byte) 1);
				// TimeStamp: 8 10 XXXX XXXX
//				long tmpTime = System.currentTimeMillis();
//				myTimestamp = myTimestamp + (int)(tmpTime-myTime);
//				myTime = tmpTime;
//				packetBuffer.put(22, (byte) 8); packetBuffer.put(23, (byte) 10);
//				packetBuffer.putInt(24, myTimestamp); packetBuffer.putInt(28, lastTimestamp);
				for(int pos=22;pos<32;++pos) {packetBuffer.put(offset+pos, (byte) 1);}
			} else if(pktType == Config.PKT_TYPE_DATA) {
				//TODO: PUSH flag. TCP Flags
				packetBuffer.put(offset+13, (byte) 16);
				// Window Size
				packetBuffer.putShort(offset+14, (short) 486); //486 * (2^5) = 15552
				
				// TCP Options
				// NOP x 2
				packetBuffer.put(offset+20, (byte) 1); packetBuffer.put(offset+21, (byte) 1);
				// TimeStamp: add till sending
//				packetBuffer.put(22, (byte) 8); packetBuffer.put(23, (byte) 10);
				for(int pos=22;pos<32;++pos) {packetBuffer.put(offset+pos, (byte) 1);}
			} else if(pktType == Config.PKT_TYPE_FINACK) {
				// TCP Flags
				packetBuffer.put(offset+13, (byte) 17);
				// Window Size
				packetBuffer.putShort(offset+14, (short) 486); //486 * (2^5) = 15552
				
				// TCP Options
				// NOP x 2
				packetBuffer.put(offset+20, (byte) 1); packetBuffer.put(offset+21, (byte) 1);
				// TimeStamp: 8 10 XXXX XXXX
//				long tmpTime = System.currentTimeMillis();
//				myTimestamp = myTimestamp + (int)(tmpTime-myTime);
//				myTime = tmpTime;
//				packetBuffer.put(22, (byte) 8); packetBuffer.put(23, (byte) 10);
//				packetBuffer.putInt(24, myTimestamp); packetBuffer.putInt(28, lastTimestamp);
				for(int pos=22;pos<32;++pos) {packetBuffer.put(offset+pos, (byte) 1);}
			} else {}
    	}
    	
    	public void run() {
    		int length = 0;
    		byte[] dataToBackByte = new byte[maxSegmentSize];
			while(true) {
				try {
					length = inTraffic.read(dataToBackByte);
				} catch (IOException e) {
					Log.e(sellerTAG, "TCP Thread ("+buyerAddr+"/"+buyerPort+"-"+internetAddr+"/"+internetPort+") receive failed: " + e.toString());
				}
				if(length > 0) {
					//Log.d(sellerTAG, "TCP Thread ("+buyerAddr+"/"+buyerPort+"-"+internetAddr+"/"+internetPort+") RECV TCP DATA: " + length);
					byte[] packetToBackByte = new byte[20+32+length];
					System.arraycopy(dataToBackByte, 0, packetToBackByte, 20+32, length);
					
					createIPHeader(packetToBackByte, 0, (short)(52+length), false, (short)0);
					createTCPHeader(packetToBackByte, 20, Config.PKT_TYPE_DATA, 32);
					
					synchronized(tcpByteList) {
						tcpByteList.add(packetToBackByte); tcpTimestampsList.add((long)-1);
						tcpSeqNoList.add(seqNoCumulative); seqNoCumulative += length;
					}
				} else if(length == -1) {break;}
    		}
			
			try {
				this.interrupt();
				sellerTCPSocket.close();
				
				if(stopWaitTime != -20) {
					DatagramPacket packetFINACK = createPKT(Config.PKT_TYPE_FINACK, 32);
    				mSocket.send(packetFINACK);
    				stopState = true;
				}
			} catch (IOException e) {
				Log.e(sellerTAG, "TCP Thread ("+buyerAddr+"/"+buyerPort+"-"+internetAddr+"/"+internetPort+") CLOSE ERROR: " + e.toString());
			}
    	}
    }
    
    private void relayUDPIncoming(byte[] packetByte, int length) {
    	ByteBuffer packetBuffer = ByteBuffer.allocate(Config.DEFAULT_MTU);
    	packetBuffer = ByteBuffer.wrap(packetByte);
    	
    	String sourceAddress = (packetByte[12] & 0xFF) + "." +
							   (packetByte[13] & 0xFF) + "." +
							   (packetByte[14] & 0xFF) + "." +
							   (packetByte[15] & 0xFF);
		String destAddress   = (packetByte[16] & 0xFF) + "." +
							   (packetByte[17] & 0xFF) + "." +
							   (packetByte[18] & 0xFF) + "." +
							   (packetByte[19] & 0xFF);
		int headerLength = (packetByte[0] & 0xf) * 4;
		int sourcePort = packetBuffer.getShort(headerLength) & 0xffff;
		int destPort = packetBuffer.getShort(headerLength+2) & 0xffff;
		packetBuffer.clear();
		
		//Log.d(sellerTAG, "Incoming UDP: "+sourceAddress+"/"+sourcePort+"->"+destAddress+"/"+destPort);
		//Message msg = new Message();
        //Bundle b = new Bundle();
        //b.putString("message", "RCV: "+sourceAddress+"/"+sourcePort+"->"+destAddress+"/"+destPort);
        //msg.setData(b);
        //mainHandler.sendMessage(msg);
		
		String buyerAddress = sourceAddress + sourcePort;
		try {
			DatagramPacket data = new DatagramPacket(packetByte, headerLength+8, length-headerLength-8,
									(new InetSocketAddress(destAddress, destPort)));
			
			if(UDPsocketMap.containsKey(buyerAddress) && UDPsocketMap.get(buyerAddress).isFeasible()) {
				//Log.d(sellerTAG, "already contained FEASIBLE UDP sellerSocket");
				UDPsocketMap.get(buyerAddress).SendPacket(data);
			} else {
				if(UDPsocketMap.containsKey(buyerAddress)) {
					UDPsocketMap.get(buyerAddress).interrupt();
					UDPsocketMap.remove(buyerAddress); //remove infeasible UDP socket
				}
				
				//Log.d(sellerTAG, "new UDP sellerSocket");
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
		private boolean feasibleFlag;
    	
    	public SellerUDPSocket(String addr, int port) throws IOException {
    		buyerAddr = addr; buyerPort = port;
    		socketIdentification = new Random().nextInt(10000);
    		feasibleFlag = true;
    		DatagramChannel sellerChannel = DatagramChannel.open();
			sellerUDPSocket = sellerChannel.socket();
			sellerUDPSocket.setSoTimeout(Config.DEFAULT_UDP_TIMEOUT);
			
			//int randomPort = new Random().nextInt(40000); randomPort += 10000;
			//SocketAddress mTunnelAddress = new InetSocketAddress(randomPort);
	        sellerUDPSocket.bind(null);
    	}
    	
    	public boolean isFeasible() {return feasibleFlag;}
    	
    	public void SendPacket(DatagramPacket packet) {
    		try {
    			//Log.d(sellerTAG, "UDP Thread("+buyerPort+") send - "+packet.getLength());
				sellerUDPSocket.send(packet);
			} catch (IOException e) {
				Log.e(sellerTAG, "UDP Thread("+buyerPort+") send failed: " + e.toString());
			}
    	}
    	
    	public void run() {
    		while(true) {
				byte[] dataToBackByte = new byte[Config.DEFAULT_MTU-100];
				DatagramPacket dataToBack = new DatagramPacket(dataToBackByte, dataToBackByte.length);
				int length = 0;
				try {
					sellerUDPSocket.receive(dataToBack);
				} catch (SocketTimeoutException e) {
					//Log.d(sellerTAG, "UDP Thread("+buyerPort+") recv TIMEOUT");
					feasibleFlag = false;
				} catch (IOException e) {
					Log.e(sellerTAG, "Seller receive UDP failed: " + e.toString());
				}
				
				if(!feasibleFlag) {
					sellerUDPSocket.close();
					break;
				} else {
					length = dataToBack.getLength();
					if(length <= 0) {continue;}
					
					//get the address of the Internet server as the source address for buyer
					String internetAddr = dataToBack.getAddress().getHostAddress();
					short internetPort = (short) dataToBack.getPort();
					//Log.d(sellerTAG, "UDP Thread("+buyerPort+") recv("+length+")");//-"+internetAddr+"/"+internetPort);
					
					byte[] packetToBackByte = new byte[Config.DEFAULT_MTU];
					System.arraycopy(dataToBackByte, 0, packetToBackByte, 28, length);
					ByteBuffer packetToBackBuffer = ByteBuffer.wrap(packetToBackByte);
					
					//version + header length, assume 20-byte IP header
					packetToBackBuffer.put(0, (byte) 69);
					//Type of Service, zeros according to WireShark packets
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
					//IP Header Checksum, first initialize to be zeros
					packetToBackBuffer.putShort(10, (short) 0);
					byte[] ipHeader = new byte[20]; System.arraycopy(packetToBackBuffer.array(), 0, ipHeader, 0, 20);
					packetToBackBuffer.putChar(10, (char) HelperFunc.calcChecksum(ipHeader));
					//Source and Destination Port
					packetToBackBuffer.putShort(20, internetPort);
					packetToBackBuffer.putShort(22, (short) buyerPort);
					//UDP Datagram Length
					packetToBackBuffer.putShort(24, (short) (8+length));
					//UDP Header Checksum, first create the pseudo IP header
					byte[] pseudoHeader = new byte[20+length]; System.arraycopy(dataToBackByte, 0, pseudoHeader, 20, length);
					pseudoHeader[0] = packetToBackBuffer.get(12); pseudoHeader[1] = packetToBackBuffer.get(13);
					pseudoHeader[2] = packetToBackBuffer.get(14); pseudoHeader[3] = packetToBackBuffer.get(15);
					pseudoHeader[4] = packetToBackBuffer.get(16); pseudoHeader[5] = packetToBackBuffer.get(17);
					pseudoHeader[6] = packetToBackBuffer.get(18); pseudoHeader[7] = packetToBackBuffer.get(19);
					pseudoHeader[8] = (byte) 0; pseudoHeader[9] = (byte) 17;
					pseudoHeader[10] = packetToBackBuffer.get(24); pseudoHeader[11] = packetToBackBuffer.get(25);
					pseudoHeader[12] = packetToBackBuffer.get(20); pseudoHeader[13] = packetToBackBuffer.get(21);
					pseudoHeader[14] = packetToBackBuffer.get(22); pseudoHeader[15] = packetToBackBuffer.get(23);
					pseudoHeader[16] = pseudoHeader[10]; pseudoHeader[17] = pseudoHeader[11];
					pseudoHeader[18] = pseudoHeader[19] = (byte) 0;
					packetToBackBuffer.putChar(26, (char) HelperFunc.calcChecksum(pseudoHeader));
					
					DatagramPacket packetToBack = null;
					try {
						//TODO: use buyerAddr instead of "192.168.49.206"
						packetToBack = new DatagramPacket(packetToBackBuffer.array(), 0, length+28,
														(new InetSocketAddress("192.168.49.206", Config.BUYER_CLIENT_PORT)));
					} catch (SocketException e) {
						Log.e(sellerTAG, "Thread("+buyerAddr+"/"+buyerPort+") create UDP pkt failed: " + e.toString());
					}
					synchronized(udpPacketsList) {
						udpPacketsList.add(packetToBack);
					}
				}
    		}
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
