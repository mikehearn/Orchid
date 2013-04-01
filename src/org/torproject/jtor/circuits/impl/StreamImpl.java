package org.torproject.jtor.circuits.impl;

import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.io.PrintWriter;
import java.util.Date;
import java.util.logging.Logger;

import org.torproject.jtor.TorException;
import org.torproject.jtor.circuits.Circuit;
import org.torproject.jtor.circuits.CircuitNode;
import org.torproject.jtor.circuits.OpenStreamResponse;
import org.torproject.jtor.circuits.Stream;
import org.torproject.jtor.circuits.cells.RelayCell;

public class StreamImpl implements Stream {
	private final static Logger logger = Logger.getLogger(StreamImpl.class.getName());
	private final static int STREAM_CONNECT_TIMEOUT = 60 * 1000;
	private final static int STREAMWINDOW_START = 500;
	private final static int STREAMWINDOW_INCREMENT = 50;
	private final static int STREAMWINDOW_MAX_UNFLUSHED = 10;
	private final CircuitBase circuit;
	private final int streamId;
	private final CircuitNode targetNode;
	private final TorInputStream inputStream;
	private final TorOutputStream outputStream;
	private boolean isClosed;
	private boolean relayEndReceived;
	private int relayEndReason;
	private boolean relayConnectedReceived;
	private final Object waitConnectLock = new Object();
	private final Object windowLock = new Object();
	private int packageWindow;
	private int deliverWindow;

	private String streamTarget = "";
	
	StreamImpl(CircuitBase circuit, CircuitNode targetNode, int streamId) {
		this.circuit = circuit;
		this.targetNode = targetNode;
		this.streamId = streamId;
		this.inputStream = new TorInputStream(this);
		this.outputStream = new TorOutputStream(this);
		packageWindow = STREAMWINDOW_START;
		deliverWindow = STREAMWINDOW_START;
	}

	void addInputCell(RelayCell cell) {
		if(isClosed)
			return;
		if(cell.getRelayCommand() == RelayCell.RELAY_END) {
			synchronized(waitConnectLock) {
				relayEndReason = cell.getByte();
				relayEndReceived = true;
				inputStream.addEndCell(cell);
				waitConnectLock.notifyAll();
			}
		} else if(cell.getRelayCommand() == RelayCell.RELAY_CONNECTED) {
			synchronized(waitConnectLock) {
				relayConnectedReceived = true;
				waitConnectLock.notifyAll();
			}
		} else if(cell.getRelayCommand() == RelayCell.RELAY_SENDME) {
			synchronized(windowLock) {
				packageWindow += STREAMWINDOW_INCREMENT;
				windowLock.notifyAll();
			}
		}
		else {
			inputStream.addInputCell(cell);
			synchronized(windowLock) { 
				deliverWindow--;
				if(deliverWindow < 0)
					throw new TorException("Stream has negative delivery window");
			}
			considerSendingSendme();
		}
	}

	private void considerSendingSendme() {
		synchronized(windowLock) {
			if(deliverWindow > (STREAMWINDOW_START - STREAMWINDOW_INCREMENT))
				return;

			if(inputStream.unflushedCellCount() >= STREAMWINDOW_MAX_UNFLUSHED)
				return;

			final RelayCell sendme = circuit.createRelayCell(RelayCell.RELAY_SENDME, streamId, targetNode);
			circuit.sendRelayCell(sendme);
			deliverWindow += STREAMWINDOW_INCREMENT;
		}
	}

	public int getStreamId() {
		return streamId;
	}

	public Circuit getCircuit() {
		return circuit;
	}

	CircuitNode getTargetNode() {
		return targetNode;
	}

	public void close() {
		if(isClosed)
			return;
		
		logger.fine("Closing stream "+ this);
		
		isClosed = true;
		inputStream.close();
		outputStream.close();
		circuit.removeStream(this);

		if(!relayEndReceived) {
			final RelayCell cell = new RelayCellImpl(circuit.getFinalCircuitNode(), circuit.getCircuitId(), streamId, RelayCell.RELAY_END);
			cell.putByte(RelayCell.REASON_DONE);
			circuit.sendRelayCellToFinalNode(cell);
		}
	}

	OpenStreamResponse openDirectory() {
		streamTarget = "[Directory]";
		final RelayCell cell = new RelayCellImpl(circuit.getFinalCircuitNode(), circuit.getCircuitId(), streamId, RelayCell.RELAY_BEGIN_DIR);
		circuit.sendRelayCellToFinalNode(cell);
		return waitForRelayConnected();
	}

	OpenStreamResponse openExit(String target, int port) {
		streamTarget = target + ":"+ port;
		final RelayCell cell = new RelayCellImpl(circuit.getFinalCircuitNode(), circuit.getCircuitId(), streamId, RelayCell.RELAY_BEGIN);
		cell.putString(target + ":"+ port);
		circuit.sendRelayCellToFinalNode(cell);
		return waitForRelayConnected();
	}

	private OpenStreamResponse waitForRelayConnected() {
		final Date startWait = new Date();
		synchronized(waitConnectLock) {
			while(!relayConnectedReceived) {

				if(relayEndReceived)
					return OpenStreamResponseImpl.createStreamError(relayEndReason);


				if(hasStreamConnectTimedOut(startWait))
					return OpenStreamResponseImpl.createStreamTimeout();

				try {
					waitConnectLock.wait(1000);
				} catch (InterruptedException e) {
					Thread.currentThread().interrupt();
					return OpenStreamResponseImpl.createStreamTimeout();
				}
			}
		}
		return OpenStreamResponseImpl.createStreamOpened(this);
	}

	private static boolean hasStreamConnectTimedOut(Date startTime) {
		final Date now = new Date();
		final long diff = now.getTime() - startTime.getTime();
		return diff >= STREAM_CONNECT_TIMEOUT;
	}

	public InputStream getInputStream() {
		return inputStream;
	}

	public OutputStream getOutputStream() {
		return outputStream;
	}

	public void waitForSendWindowAndDecrement() {
		waitForSendWindow(true);
	}

	public void waitForSendWindow() {
		waitForSendWindow(false);
	}

	public void waitForSendWindow(boolean decrement) {
		synchronized(windowLock) {
			while(packageWindow == 0) {
				try {
					windowLock.wait();
				} catch (InterruptedException e) {
					throw new TorException("Thread interrupted while waiting for stream package window");
				}
			}
			if(decrement)
				packageWindow--;
		}
		targetNode.waitForSendWindow();
	}

	public String toString() {
		return "[Stream stream_id="+ streamId + " circuit="+ circuit +" target="+ streamTarget +"]";
	}

	public void dashboardRender(PrintWriter writer, int flags) throws IOException {
		writer.print("     ");
		writer.print("[Stream stream_id="+ streamId + " cid="+ circuit.getCircuitId());
		if(relayConnectedReceived) {
			writer.print(" sent="+outputStream.getBytesSent() + " recv="+ inputStream.getBytesReceived());
		} else {
			writer.print(" (waiting connect)");
		}
		writer.print(" target="+ streamTarget);
		writer.println("]");
	}
}
