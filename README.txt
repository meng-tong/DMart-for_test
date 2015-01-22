Seller.java
========================framework========================
onCreate():
	- initialize UDPsocketMap/udpPacketsList and TCPsocketap
	- fix the seller's flow control window to be 144480

onClick1(): the reaction function for "connect" button
	- create the mainHandler for UI updating
	- start incomingThread and post waitForVpnConnection
	- mark the isConnected flag

waitForVpnConnection: start the UDP socket for connections from buyers
	- open the DatagramChannel, configure to blocking mode
	- bind the mSocket to the fixed port, mark the isConnected flag
	- post relayIncoming within the incomingThread
	- start outgoingThread and post relayOutgoing
=========================================================
=======================relayIncoming=======================
relayIncoming: process the packets from buyers and forward to cellular
	- call mSocket.receive(packet), blocking until packets arrive
	- only forward UDP/TCP packets
--------------------------------------Incoming UDP--------------------------------------
relayUDPIncoming(byte[] packetByte, int length):
	- extract sourceAddress/destAddress and sourcePort/destPort
	- assume 8-byte UDP header, wrap data in a DatagramPacket
	- use string of sourceAddress+sourcePort as buyer's identity
	- for new buyer, create SellerUDPSocket and put in UDPsocketMap
	- call SendPacket(data) of the corresponding SellerUDPSocket

SellerUDPSocket: self-defined class for a unique buyer addr/port
	- sellerUDPSocket for the DatagramSocket to cellular networks
	- buyerAddr/buyerPort as the buyer address identity
	- socketIdentification for the cumulating identification number
	- feasibleFlag helping to identify infeasible socket object
	- use sellerUDPSocket.bind(null) to bind to random fixed port
	- socket timeout to be Config.EDFAULT_UDP_TIMEOUT
	- SendPacket(DtagramPacket): send the UDP data

SellerUDPSocket.run(): UDP forwarding thread for unique buyer addr/port
	- call sellerUDPSocket.receive(dataToBack) to receive from cellular
	- timeout when receiving turns the socket infeasible
	- call getAddress() and getPort() to track back Internet addr/port
	- recreate the IP+UDP header and wrap into DatagramPacket
	- add the UDP packet to udpPacketsList (refer to handle outgoing)
--------------------------------------Incoming TCP--------------------------------------
relayTCPIncoming(byte[] packetByte, int length):
	- extract sourceAddress/destAddress and sourcePort/destPort
	- use string of source/dest Address/Port as TCP connection identity
	- for new connection, create SellerTCPSocket and put in TCPsocketMap
	- headerLength for IP header length, offset for TCP header length
	- call handlePacket(packetByte, headerLength, offset, length, flags)

SellerTCPSocket: self-defined class for a unique TCP connection
	- buyerAddr/buyerPort as the buyer address
	- internetAddr/internetPort as the Internet address
	- sellerTCPSocket as the TCP socket to the cellular
	- outTraffic as output stream to the cellular
	- inTraffic as input stream from the cellular
	- state as the flag denoting the TCP connection state
	- tcpByteList/tcpSeqNoList/tcpTimestampsList maintain packets to buyers
	- consumedWindow for sent but un-ACKed packet size
	-* lastAckNo for last received acknowledge number
	-* countAck seems useless now
	- socketIdentification for the cumulating identification number
	- seqNoCumulative for cumulating sequence number
	- seqNoAcked for the most recently acked sequence number
	- ackNo for the next seq number wanna hear from buyer
	- buyerFlowControlWindow/~Scale as implied by name
	- maxSegmentSize for the maximum segment size allowed by buyer

SellerTCPSocket.run(): TCP forwarding thread for unique TCP connection