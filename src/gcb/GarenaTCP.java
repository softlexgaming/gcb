/*
 * To change this template, choose Tools | Templates
 * and open the template in the editor.
 */

package gcb;

import java.io.DataInputStream;
import java.io.DataOutputStream;
import java.io.IOException;
import java.net.InetAddress;
import java.net.Socket;
import java.net.SocketTimeoutException;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.util.ArrayList;
import java.util.HashMap;
import org.apache.commons.configuration.ConversionException;

/**
 *
 * @author wizardus
 */
public class GarenaTCP extends Thread {
	boolean terminated; //termination flag
	int conn_id; //this virtual TCP connection identifier
	long last_time; //last time in milliseconds that a packet was sent
	long last_received; //last time in milliseconds that a packet was received from GHost++

	String local_hostname; //local server hostname
	int[] local_ports; //port on local server we are connected to

	int remote_id; //remote user ID
	String remote_username;
	InetAddress remote_address;
	int remote_port;

	//not thread safe objects
	ArrayList<GarenaTCPPacket> packets; //to transmit to Garena
	HashMap<Integer, GarenaTCPPacket> out_packets; //sequence number -> packet; to transmit to GHost++
	ByteBuffer out_buffer; //if buffered output is use, only full packets will be sent and packets will be dissected to correct information
	String[] reservedNames;

	GarenaInterface garena;
	Socket socket;
	DataOutputStream out;
	DataInputStream in;
	ByteBuffer buf;

	boolean tcpDebug;
	boolean localBuffered;
	
	//dynamic connection properties
	Integer seq; //our current sequence number
	Integer ack; //our current acknowledgement number
	
	boolean rttMade; //whether we have made a round trip time measurement
	double smoothedRTT; //smoothed round trip time
	double rttVariation; //round-trip time variation
	int retransmissionTimeout; //current retransmission timeout; at first set to standardDelay
	
	//static connection properties
	int maximumBufferedPackets; //max number of packets to buffer before stopping transmission
	int standardDelay; //delay until packets are retransmitted
	int soTimeout; //timeout before doing standard retransmission instead of reading
	double srttAlpha; //alpha value, see rfc2988
	double srttBeta; //beta value, see rfc2988
	double srttLower; //minimum round trip time
	double srttUpper; //maximum round trip time
	double srttClockGranularity; //clock granularity, default to 1 ms
	int srttK; //no idea, but RFC2988 says it is 4

	public GarenaTCP(GarenaInterface garena) {
		this.garena = garena;
		buf = ByteBuffer.allocate(65536);
		packets = new ArrayList<GarenaTCPPacket>();
		out_packets = new HashMap<Integer, GarenaTCPPacket>();

		terminated = false;
		last_time = System.currentTimeMillis();
		last_received = System.currentTimeMillis();
		seq = 0;
		ack = 0;

		//configuration
		local_hostname = GCBConfig.configuration.getString("gcb_tcp_host", "192.168.1.1");

		try {
			String[] local_ports_str = GCBConfig.configuration.getStringArray("gcb_tcp_port");
			local_ports = new int[local_ports_str.length];

			for(int i = 0; i < local_ports_str.length; i++) {
				try {
					local_ports[i] = Integer.parseInt(local_ports_str[i]);
				} catch(NumberFormatException e) {
					Main.println("[GarenaTCP] Configuration warning: unable to parse " + local_ports_str[i]);
					local_ports[i] = -1;
				}
			}
		} catch(ConversionException e) {
			Main.println("[GarenaTCP] Configuration error: while parsing gcb_tcp_port as string array");
			local_ports = new int[] {};
		}

		try {
			reservedNames = GCBConfig.configuration.getStringArray("gcb_tcp_reservednames");
		} catch(ConversionException e) {
			Main.println("[GarenaTCP] Configuration error: while parsing gcb_tcp_reservednames as string array");
			reservedNames = new String[] {};
		}

		tcpDebug = GCBConfig.configuration.getBoolean("gcb_tcp_debug", false);

		boolean useBufferedOutput = GCBConfig.configuration.getBoolean("gcb_tcp_buffer", true);
		if(useBufferedOutput) {
			out_buffer = ByteBuffer.allocate(65536 * 2);
			out_buffer.order(ByteOrder.LITTLE_ENDIAN);
		}
		
		localBuffered = GCBConfig.configuration.getBoolean("gcb_tcp_localbuffer", true);
		
		//connection properties
		maximumBufferedPackets = GCBConfig.configuration.getInt("gcb_tcp_maxbufferedpackets", 0);
		standardDelay = GCBConfig.configuration.getInt("gcb_tcp_standarddelay", 3000);
		soTimeout = GCBConfig.configuration.getInt("gcb_tcp_sotimeout", 1000);
		srttAlpha = GCBConfig.configuration.getDouble("gcb_tcp_srttalpha", 0.125);
		srttBeta = GCBConfig.configuration.getDouble("gcb_tcp_srttbeta", 0.25);
		srttLower = GCBConfig.configuration.getDouble("gcb_tcp_srttlower", 10);
		srttUpper = GCBConfig.configuration.getDouble("gcb_tcp_srttupper", 60000);
		srttClockGranularity = GCBConfig.configuration.getDouble("gcb_tcp_srttg", 1);
		srttK = GCBConfig.configuration.getInt("gcb_tcp_srttk", 4);
		
		rttMade = false;
		retransmissionTimeout = standardDelay;
	}

	public boolean isValidPort(int port) {
		for(int i = 0; i < local_ports.length; i++) {
			if(local_ports[i] == port && local_ports[i] != -1) return true;
		}

		return false;
	}

	public boolean init(InetAddress remote_address, int remote_port, int remote_id, int conn_id, int destination_port, MemberInfo member) {
		this.remote_address = remote_address;
		this.remote_port = remote_port;
		this.remote_id = remote_id;
		this.conn_id = conn_id;

		if(member != null) {
			this.remote_username = member.username;
		} else {
			this.remote_username = remote_id + "";
		}

		Main.println("[GarenaTCP] Starting new virtual TCP connection " + conn_id +
				" with user " + remote_username + " at " + remote_address + " to " + destination_port);

		//make sure their username is not reserved
		if(isReservedName(remote_username)) {
			Main.println("[GarenaTCP] User " + remote_username + " at " + remote_address + " in connection " + conn_id + " tried to use a reserved name");
			end();
			return false;
		}

		if(!isValidPort(destination_port)) {
			Main.println("[GarenaTCP] User " + remote_username + " tried to connect on port " + destination_port + "; terminating");
			end();
			return false;
		} else {
			//establish real TCP connection with GHost (hopefully)
			Main.println("[GarenaTCP] Connecting to GAMEHOST at " + local_hostname + " on port " + destination_port + " for connection " + conn_id);
			try {
				InetAddress local_address = InetAddress.getByName(local_hostname);
				socket = new Socket(local_address, destination_port);

				out = new DataOutputStream(socket.getOutputStream());
				in = new DataInputStream(socket.getInputStream());
			} catch(IOException ioe) {
				end();
				
				if(Main.DEBUG) {
					ioe.printStackTrace();
				}
				
				return false;
			}

			//lastly, send the GCBI packet with information about the remote user
			if(GCBConfig.configuration.getBoolean("gcb_enablegcbi", false)) {
				buf.clear();
				buf.order(ByteOrder.LITTLE_ENDIAN);
				buf.put((byte) Constants.GCBI_HEADER_CONSTANT);
				buf.put((byte) Constants.GCBI_INIT);
				buf.putShort((short) 22);
				buf.order(ByteOrder.BIG_ENDIAN);
				buf.put(remote_address.getAddress());
				buf.putInt(remote_id);
				buf.putInt(garena.room_id);

				if(member != null) {
					buf.putInt(member.experience);
					buf.put(member.country.substring(0, 2).getBytes());
				} else {
					buf.putInt(-1);
					buf.put("??".getBytes());
				}

				writeOutData(buf.array(), 0, buf.position(), true);
			}

			start();
			return true;
		}
	}

	public void initReverse(InetAddress remote_address, int remote_port, int remote_id, int conn_id, Socket socket) {
		this.remote_address = remote_address;
		this.remote_port = remote_port;
		this.remote_id = remote_id;
		this.conn_id = conn_id;
		this.socket = socket;

		try {
			out = new DataOutputStream(socket.getOutputStream());
			in = new DataInputStream(socket.getInputStream());
		} catch(IOException ioe) {
			end();
			
			if(Main.DEBUG) {
				ioe.printStackTrace();
			}
		}

		Main.println("[GarenaTCP] Starting new reverse virtual TCP " + conn_id + " with " + remote_address + " on port " + remote_port);
		start();
	}

	//called on acknowledgement from remote Garena user
	public void connAck(int seq, int ack) {
		if(terminated) return;

		if(tcpDebug) {
			Main.println("[GarenaTCP] debug@connack@" + System.currentTimeMillis() + ": received acknowledge for " + seq + ", remote ack=" + ack + " in connection " + conn_id);
		}

		//acknowledge packets =seq or <ack
		synchronized(packets) {
			for(int i = 0; i < packets.size(); i++) {
				GarenaTCPPacket curr = packets.get(i);
				if(curr.seq < ack || curr.seq == seq) {
					acknowledgePacket(i);
				}
			}
		}

		//fast retransmission: resend packets from ack to seq-1
		if(ack < seq) {
			synchronized(packets) {
				for(int i = 0; i < packets.size(); i++) {
					GarenaTCPPacket curr = packets.get(i);
					if(!curr.fastRetransmitted && curr.seq >= ack && curr.seq <= seq - 1) {
						curr.send_time = System.currentTimeMillis();
						curr.fastRetransmitted = true;
						curr.timesSent++;

						garena.sendTCPData(remote_address, remote_port, conn_id, lastTime(), curr.seq, this.ack, curr.data, curr.data.length, buf);

						if(tcpDebug) {
							Main.println("[GarenaTCP] debug@connack@" + System.currentTimeMillis() + ": fast retransmitting seq=" + curr.seq + " in connection " + conn_id);
						}
					}
				}
			}
		}
		
		standardRetransmission();
	}

	//called when data is received from remote Garena user
	public void data(int seq, int ack, byte[] data, int offset, int length) {
		if(terminated) return;

		if(tcpDebug) {
			Main.println("[GarenaTCP] debug@data@" + System.currentTimeMillis() + ": received SEQ=" + seq + "; remote ACK=" + ack + "; len=" + length + " in connection " + conn_id);
		}

		//acknowledge packets
		synchronized(packets) {
			for(int i = 0; i < packets.size(); i++) {
				GarenaTCPPacket curr = packets.get(i);
				if(curr.seq < ack) {
					acknowledgePacket(i);
				}
			}
		}
		
		standardRetransmission();

		//pass data on to local server

		if(seq == this.ack) {
			synchronized(this.ack) {
				this.ack++;
			}

			writeOutData(data, offset, length, false);
			
			//send any other packets that we have stored
			synchronized(out_packets) {
				GarenaTCPPacket packet = out_packets.get(this.ack);
				while(packet != null) {
					out_packets.remove(this.ack);

					synchronized(this.ack) {
						this.ack++;
					}

					if(tcpDebug) {
						Main.println("[GarenaTCP] debug@data@" + System.currentTimeMillis() + ": sending stored packet to GHost++, SEQ=" + packet.seq + " in connection " + conn_id);
					}

					writeOutData(packet.data, false);

					packet = out_packets.get(this.ack);
				}
			}
		} else if(seq > this.ack) {
			//store the packet, we'll send it later
			byte[] copy = new byte[length];
			System.arraycopy(data, offset, copy, 0, length);
			GarenaTCPPacket packet = new GarenaTCPPacket();
			packet.seq = seq;
			packet.data = copy;

			if(tcpDebug) {
				Main.println("[GarenaTCP] debug@data@" + System.currentTimeMillis() + ": storing remote packet, SEQ=" + packet.seq + "; our ACK=" + this.ack + " in connection " + conn_id);
			}

			synchronized(out_packets) {
				out_packets.put(seq, packet);
			}
		} else {
			//ignore packet if seq is less than our ack
			if(tcpDebug) {
				Main.println("[GarenaTCP] debug@data@" + System.currentTimeMillis() + ": ignoring remote packet, SEQ=" + seq + "; our ACK=" + this.ack + " in connection " + conn_id);
			}
		}

		//send conn ack
		if(tcpDebug) {
			Main.println("[GarenaTCP] debug@data@" + System.currentTimeMillis() + ": acknowledging " + seq + "; our ACK=" + this.ack + " in connection " + conn_id);
		}
		
		garena.sendTCPAck(remote_address, remote_port, conn_id, lastTime(), seq, this.ack, buf);

		//if buffered output, extract packets from out_buffer and process
		if(out_buffer != null) {
			while(out_buffer.position() >= 4) {
				int header = GarenaEncrypt.unsignedByte(out_buffer.get(0));

				//validate header
				if(header == Constants.W3GS_HEADER_CONSTANT) {
					int oLength = GarenaEncrypt.unsignedShort(out_buffer.getShort(2));
					
					if(tcpDebug) {
						Main.println("[GarenaTCP] debug@" + System.currentTimeMillis() + ": " + conn_id + " out buffered header=" + header + ", length=" + oLength);
					}

					//validate length; minimum packet legnth is 4
					if(oLength >= 4) {
						if(out_buffer.position() >= oLength) {
							//write the data if process returns true, else disconnect
							processOutData(out_buffer, oLength);

							//reset buffer: move everything so buffer starts at zero
							int remainingBytes = out_buffer.position() - oLength;
							System.arraycopy(out_buffer.array(), oLength, out_buffer.array(), 0, remainingBytes);
							out_buffer.position(remainingBytes);
						} else {
							//not enough bytes yet
							return;
						}
					} else {
						Main.println("[GarenaTCP] Received invalid length in connection " + conn_id + ", disconnecting");
						end();
						return;
					}
				} else {
					Main.println("[GarenaTCP] Received invalid header " + header + " in connection " + conn_id + ", disconnecting");
					end();
					return;
				}
			}
		}
	}

	//processes one packet from the output buffer to local WC3 host
	public void processOutData(ByteBuffer buf, int length) {
		int old_position = buf.position();

		if(GarenaEncrypt.unsignedByte(buf.get(1)) == Constants.W3GS_REQJOIN) {
			if(length > 20) {
				buf.position(4);
				int hostCounter = buf.getInt();
				int entryKey = buf.getInt();
				byte unknown = buf.get();
				short listenPort = buf.getShort();
				int peerKey = buf.getInt();
				String name = GarenaEncrypt.getTerminatedString(buf);

				int remainderLength = length - buf.position();

				if(!name.equalsIgnoreCase(remote_username)) {
					Main.println("[GarenaTCP] User " + remote_username + " in connection " + conn_id + " attempted to use an invalid name: " + name);
				}

				//add part after username + part before username + name + null terminator
				byte[] remote_username_bytes = remote_username.getBytes();
				int rewrittenLength = remainderLength + 19 + remote_username_bytes.length + 1;

				//rewrite data to use their actual Garena username
				ByteBuffer rewrittenData = ByteBuffer.allocate(rewrittenLength);
				rewrittenData.order(ByteOrder.LITTLE_ENDIAN);

				rewrittenData.put((byte) Constants.W3GS_HEADER_CONSTANT);
				rewrittenData.put((byte) Constants.W3GS_REQJOIN);
				rewrittenData.putShort((short) rewrittenLength);
				rewrittenData.putInt(hostCounter);

				//if we're using gcb_broadcastfilter_key, then we've been broadcasting a fake entry key to Garena
				//replace with the actual entry key here
				if(GCBConfig.configuration.getBoolean("gcb_broadcastfilter_key")) {
					WC3GameIdentifier identifier = garena.getWC3Interface().getGameIdentifier(entryKey);
					
					if(identifier != null) {
						rewrittenData.putInt(identifier.ghostEntryKey);
						Main.debug("[GarenaTCP] Rewrote entry key (" + entryKey + " -> " + identifier.ghostEntryKey + ")");
					} else {
						rewrittenData.putInt(entryKey);
					}
				} else {
					rewrittenData.putInt(entryKey);
				}

				rewrittenData.put(unknown);
				rewrittenData.putShort(listenPort);
				rewrittenData.putInt(peerKey);
				rewrittenData.put(remote_username_bytes);
				rewrittenData.put((byte) 0);
				rewrittenData.put(buf.array(), buf.position(), remainderLength);

				//force so that it doesn't go straight back into the output buffer
				writeOutData(rewrittenData.array(), true);
			} else {
				Main.println("[GarenaTCP] Invalid length in join request in connection " + conn_id);
				end();
				return;
			}
		} else {
			writeOutData(buf.array(), 0, length, true);
		}

		buf.position(old_position);
	}

	public void writeOutData(byte[] data, boolean force) {
		writeOutData(data, 0, data.length, force);
	}

	public void writeOutData(byte[] data, int offset, int length, boolean force) {
		if(out_buffer == null || force) {
			try {
				out.write(data, offset, length);
			} catch(IOException ioe) {
				if(Main.DEBUG) {
					ioe.printStackTrace();
				}
			}
		} else {
			//write to our output buffer to later extract packets
			out_buffer.put(data, offset, length);
		}
	}
	
	public void standardRetransmission() {
		//standard retransmission: resend old packets
		synchronized(packets) {
			for(int i = 0; i < packets.size(); i++) {
				GarenaTCPPacket curr = packets.get(i);

				if(curr.send_time < System.currentTimeMillis() - retransmissionTimeout) {
					curr.send_time = System.currentTimeMillis();
					curr.timesSent++;
					
					//double the retransmission timeout
					retransmissionTimeout *= 2;
					
					//don't fast retransmit this packet since we standard retranmsitted it
					curr.fastRetransmitted = true;
					
					garena.sendTCPData(remote_address, remote_port, conn_id, lastTime(), curr.seq, this.ack, curr.data, curr.data.length, buf);

					if(tcpDebug) {
						Main.println("[GarenaTCP] debug@" + System.currentTimeMillis() + ": standard retransmitting in connection " + conn_id);
					}
				}
			}
		}
	}
	
	public void acknowledgePacket(int x) {
		//acknowledge a single packet
		synchronized(packets) {
			GarenaTCPPacket packet = packets.remove(x);
			packets.notifyAll();
			x--;
			
			if(packet.timesSent == 1) {
				//update standard retransmission timeout
				double roundTripTime = System.currentTimeMillis() - packet.send_time;

				//impose limitations on round trip time
				if(roundTripTime < srttLower) {
					roundTripTime = srttLower;
				} else if(roundTripTime > srttUpper) {
					roundTripTime = srttUpper;
				}

				if(!rttMade) {
					smoothedRTT = roundTripTime;
					rttVariation = roundTripTime / 2;
					
					rttMade = true;
				} else {
					rttVariation = (1 - srttBeta) * rttVariation + srttBeta * Math.abs(smoothedRTT - roundTripTime);
					smoothedRTT = (1 - srttAlpha) * smoothedRTT + srttAlpha * roundTripTime;
				}

				retransmissionTimeout = (int) Math.ceil(smoothedRTT + Math.max(srttClockGranularity, srttK * rttVariation));

				if(tcpDebug) {
					Main.println("[GarenaTCP] debug@" + System.currentTimeMillis() + ": " + conn_id + " setting retransmission timeout to " + retransmissionTimeout);
				}
			}
		}
	}

	public void run() {
		byte[] rbuf = new byte[65536];
		ByteBuffer lbuf = ByteBuffer.allocate(65536);
		
		try {
			socket.setSoTimeout(soTimeout);
		} catch(IOException e) {
			//not a major problem, this is only necessary in special circumstances
			
			if(tcpDebug) {
				Main.println("[GarenaTCP] debug@" + System.currentTimeMillis() + ": setting soTimeout failed, but continuing");
			}
		}

		while(!terminated) {
			//do standard retransmission every now and then
			standardRetransmission();
			
			try {
				int len;
				
				if(localBuffered) {
					//read packet header, which includes packet length
					in.readFully(rbuf, 0, 4);
					len = GarenaEncrypt.unsignedByte(rbuf[2]) + GarenaEncrypt.unsignedByte(rbuf[3]) * 256;
					
					if(len >= 4) {
						in.readFully(rbuf, 4, len - 4);
					} else  {
						Main.println("[GarenaTCP] Read invalid packet length (len=" + len + "), terminating");
						end();
						break;
					}
				} else {
					//read as many bytes as we can and relay them onwards to remote
					len = in.read(rbuf); //definitely _don't_ want to readfully here!
					last_received = System.currentTimeMillis();

					if(len == -1) {
						Main.println("[GarenaTCP] Local host for connection " + conn_id + " disconnected");
						end();
						break;
					}
				}

				byte[] data = new byte[len];
				System.arraycopy(rbuf, 0, data, 0, len);
				
				if(tcpDebug) {
					Main.println("[GarenaTCP] debug@" + System.currentTimeMillis() + ": " + conn_id + " new packet from local: " + seq + " (len=" + len + ")");
				}

				//save packet in case it doesn't go through
				GarenaTCPPacket packet = new GarenaTCPPacket();
				packet.seq = seq;
				packet.data = data;

				synchronized(packets) {
					while(maximumBufferedPackets != 0 && packets.size() > maximumBufferedPackets) { //let's wait a while before sending more
						if(tcpDebug) {
							Main.println("[GarenaTCP] debug@" + System.currentTimeMillis() + ": " + conn_id + " waiting because of " + packets.size() + " packets");
						}
						
						try {
							packets.wait(100);
						} catch(InterruptedException e) {}
						
						//continue to standard retransmit packets
						standardRetransmission();
					}

					packets.add(packet);
				}

				//don't use buf here so there isn't thread problems
				garena.sendTCPData(remote_address, remote_port, conn_id, lastTime(), seq, ack, data, len, lbuf);

				//increment sequence number
				synchronized(seq) {
					seq++;
				}
			} catch(SocketTimeoutException e) {
				//continue loop with standard retransmission
			} catch(IOException ioe) {
				end();
				
				if(Main.DEBUG) {
					ioe.printStackTrace();
				}
				
				break;
			}
		}
	}

	private synchronized long lastTime() {
//		//return current last_time and set last_time to current time
//		if(last_time == -1) {
//			last_time = System.nanoTime();
//			return last_time;
//		} else {
//			long old_time = last_time;
//			last_time = System.nanoTime();
//			return old_time;
//		}
		//this method is synchronized and only one that edits last_time
		last_time = System.currentTimeMillis();
		return -1; //todo: implement timestamp correctly in GarenaInterface
	}

	public void end() {
		Main.println("[GarenaTCP] Terminating connection " + conn_id + " with " + remote_address + " (" + remote_username + ")");
		terminated = true;
		//allocate a new buffer so we don't do anything thread-unsafe
		ByteBuffer tbuf = ByteBuffer.allocate(65536);

		if(socket != null) {
			try {
				socket.close();
			} catch(IOException ioe) {
				ioe.printStackTrace();
			}
		}

		//send four times because that's what the standard client does
		garena.sendTCPFin(remote_address, remote_port, conn_id, last_time, tbuf);
		garena.sendTCPFin(remote_address, remote_port, conn_id, last_time, tbuf);
		garena.sendTCPFin(remote_address, remote_port, conn_id, last_time, tbuf);
		garena.sendTCPFin(remote_address, remote_port, conn_id, last_time, tbuf);

		//remove connection from GarenaInterface map
		garena.removeTCPConnection(conn_id);
	}

	//check if name is in the list of reserved names
	public boolean isReservedName(String test) {
		for(String name : reservedNames) {
			if(name.equalsIgnoreCase(test)) {
				return true;
			}
		}

		return false;
	}

	public boolean isTimeout() {
		long minTime = Math.min(System.currentTimeMillis() - last_time, System.currentTimeMillis() - last_received);
		if(minTime > 300000) {
			return true;
		} else {
			return false;
		}
	}
}

class GarenaTCPPacket {
	int seq; //this packet's sequence number
	long send_time; //time that this packet was last sent (including both retransmission)
	byte[] data;
	boolean fastRetransmitted; //only fast retransmit packets once
	int timesSent; //how many times this packet was sent, including both retransmission

	public GarenaTCPPacket() {
		fastRetransmitted = false;
		timesSent = 1;
		
		send_time = System.currentTimeMillis();
	}
}
