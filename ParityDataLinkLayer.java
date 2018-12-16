import java.util.Deque;
import java.util.Iterator;
import java.util.LinkedList;
import java.util.Queue;

/**
 * 
 */

/**
 * @author HKhawaja
 * @author Scott F. Kaplan (sfkaplan@cs.amherst.edu)
 *
 */
public class ParityDataLinkLayer extends DataLinkLayer {
	
	// ===============================================================
    // The start tag, stop tag, and the escape tag.
    private final byte startTag  = (byte)'{';
    private final byte stopTag   = (byte)'}';
    private final byte escapeTag = (byte)'\\';
    private byte sendingFrameNumber = 0;
    private byte expectedReceivingFrameNumber = 0;
    private byte dataIndicator = 0;
    private byte ackIndicator = 1;
    private byte nackIndicator = 2;
    private byte latestAckReceived = -1;
    private byte latestNackReceived = -1;
    // ===============================================================


	/* Takes an array of bytes, determines the parity bit for the entire array
	 * and appends it to the start of the data, while framing the entire data
	 * 
	 * @param data: the data to frame
	 * @return the framed data
	 * @see DataLinkLayer#createFrame(byte[])
	 */
	@Override
	protected byte[] createFrame(byte[] data) {
		Deque<Byte> frameToSend = new LinkedList<>();
		
		int parityVal = 0;
		
		//handle adding Sending Frame # and Data representer first
		//compute number of ones in those
		addData(frameToSend, sendingFrameNumber);
		parityVal += sumOnes(sendingFrameNumber);
		addData(frameToSend, dataIndicator);
		parityVal += sumOnes(dataIndicator);
		
		//sum number of 1s in data
		for (int i = 0; i<data.length; i++) {
			parityVal += sumOnes(data[i]);
			addData(frameToSend, data[i]);
		}
		
		//compute parity value over frame
		if (parityVal%2 == 0) {
			frameToSend.addFirst((byte) 0);
		}
		else {
			frameToSend.addFirst((byte) 1);
		}
		
		//then append start and stop tags
		frameToSend.addFirst(startTag);
		frameToSend.add(stopTag);
				
		//finally, convert dequeue to byte array
		int counter = 0;
		byte[] frameToTransmit = new byte[frameToSend.size()];
		for (byte toAdd: frameToSend) {
			frameToTransmit[counter] = toAdd;
			counter++;
		}
		return frameToTransmit;
	}

	private void addData(Deque<Byte> frameToTransmit, byte b) {
		if (b == startTag || b == stopTag || b == escapeTag) {
			frameToTransmit.add(escapeTag);
		}
		frameToTransmit.add(b);		
	}

	private int sumOnes(byte data) {		
		if (data >= 0) {
			//counter variable
			int numOnes = 0;
			
			//check if last bit is 1
			//shift data to right by one bit until data == 0
			while (data != 0) {
				if ((1 & data) > 0) {
					numOnes++;
				}
				data = (byte) (data>>>1);
			}
			return numOnes;
		}
		//if negative, make sure byte isn't sign extended
		else {
			int numOnes = 0;
			while (data != 0) {
				if ((1 & data) > 0) {
					numOnes++;
				}
				data = (byte) ((data & 0xff)>>>1);
			}
			return numOnes;
		}
	}

	/* 
	 * @see DataLinkLayer#processFrame()
	 * 
	 */
	@Override
	protected Queue<Byte> processFrame() {
		
		// Search for a start tag.  Discard anything prior to it.
		boolean        startTagFound = false;
		Iterator<Byte>             i = receiveBuffer.iterator();
		while (!startTagFound && i.hasNext()) {
		    byte current = i.next();
		    if (current != startTag) {
			i.remove();
		    } else {
			startTagFound = true;
		    }
		}

		// If there is no start tag, then there is no frame.
		if (!startTagFound) {
//			System.out.println("An error has occurred! No start tag was found.");
		    return null;
		}

		// Try to extract data while waiting for an unescaped stop tag.
		Queue<Byte> extractedBytes = new LinkedList<Byte>();
		boolean       stopTagFound = false;
		while (!stopTagFound && i.hasNext()) {
		    // Grab the next byte.  If it is...
		    //   (a) An escape tag: Skip over it and grab what follows as
		    //                      literal data.
		    //   (b) A stop tag:    Remove all processed bytes from the buffer and
		    //                      end extraction.
		    //   (c) A start tag:   All that precedes is damaged, so remove it
		    //                      from the buffer and restart extraction.
		    //   (d) Otherwise:     Take it as literal data.
		    byte current = i.next();
		    if (current == escapeTag) {
			if (i.hasNext()) {
			    current = i.next();
			    extractedBytes.add(current);
			} else {
			    // An escape was the last byte available, so this is not a
			    // complete frame.
			    return null;
			}
		    } else if (current == stopTag) {
			cleanBufferUpTo(i);
			stopTagFound = true;
		    } else if (current == startTag) {
			cleanBufferUpTo(i);
			extractedBytes = new LinkedList<Byte>();
		    } else {
			extractedBytes.add(current);
		    }

		}

		// If there is no stop tag, then the frame is incomplete.
		if (!stopTagFound) {
//			System.out.println("An error has occurred! No stop tag was found.");
		    return null;
		}
		return extractedBytes;
	}
	
	private void cleanBufferUpTo (Iterator<Byte> end) {
		Iterator<Byte> i = receiveBuffer.iterator();
		while (i.hasNext() && i != end) {
		    i.next();
		    i.remove();
		}
	}

	@Override
	protected void finishFrameSend() {
		//if latest ack number received corresponds to frame number 
		//of most recently sent frame, all is well. 
		//flip sending frame number from 0 to 1 or 1 to 0; situation parfaite!
		if (latestAckReceived == sendingFrameNumber) {
			sendingFrameNumber = (byte) ((sendingFrameNumber + 1)%2);
		}
		//if latest nack number received corresponds to frame number 
		//of most recently sent frame, data got corrupted along the way
		//we need to resend the latest frame sent 
		else if (latestNackReceived == sendingFrameNumber) {
			transmit(latestFrameSent);
			finishFrameSend();
		}
		//if no ack came in and no nack came in either,
		//we should resend the latest frame sent 
		else {
			transmit(latestFrameSent);
			finishFrameSend();
		}
	}

	@Override
	protected void finishFrameReceive(Queue<Byte> data) {
		//extract parityVal
		int parityVal = data.remove();
		
		//Recompute parityVal over the rest of the frame 
		//and compare to retrieved parityVal computed at sender's
		int recomputedParity = 0;
		for (Byte dataByte: data) {
			recomputedParity+= sumOnes(dataByte);
		}
		recomputedParity%=2;
		
		//extract frame Number and frame type
		byte frameNumber = data.remove();
		byte frameType = data.remove();
		
		//if retrieved parityVal unexpected number or != computed parity, there's an issue
		//send a Nack for the received frame
		if(parityVal != 0 && parityVal != 1) {
			System.out.println("An error was detected! The parity bit was corrupted");
			sendNack(frameNumber);
			return;
		}
		
		if (parityVal != recomputedParity) {
			System.out.println("A parity error was detected!");
			System.out.println("Corrupted data: " + new String(toByteArray(data)));
			sendNack(frameNumber);
			return;
		}
		
		//if we get to this point, there were no parity errors
		//if the frame is an ack or a nack, handle those cases using 
		//special methods
		if (frameType == ackIndicator) {
			handleAckReceivedBySender(frameNumber);
			return;
		}
		
		if (frameType == nackIndicator) {
			handleNackReceivedBySender(frameNumber);
			return;
		}
		
		//if frame type is not a data frame either, then something went wrong
		//send a nack
		if (frameType != dataIndicator) {
			sendNack(frameNumber);
			return;
		}
				
		//If we get to this stage, data is uncorrupted. So, convert it to byte array 
		//only send to client if the frame number is the expected receiving frame number
		//if data sent to client, flip Expected Frame Number to receive from 0 to 1 or 1 to 0
		//send an Ack with frame number of the frame just received 
		if (debug) {
		    System.out.println("ParityDataLinkLayer.processFrame(): Got whole frame!");
		}
		byte [] dataReceived = toByteArray(data);
		if (frameNumber == expectedReceivingFrameNumber) {
			client.receive(dataReceived);
			expectedReceivingFrameNumber = (byte) ((expectedReceivingFrameNumber + 1) % 2);
		}
		
		sendAck(frameNumber);
	}
	
	private void handleNackReceivedBySender(byte frameNumber) {
		//update the frame number of the latest nack received
		//we also need to set latest ack to -1 so that the sender knows
		//the latest frame received was a Nack and not an ack and doesn't get confused
		latestAckReceived = -1;
		latestNackReceived = frameNumber;
		
	}

	private void handleAckReceivedBySender(byte frameNumber) {
		//update the frame number of the latest ack received
		//we also need to set latest nack received to -1 so that the sender knows
		//the latest frame received was an ack and not a nack and doesn't get confused
		latestAckReceived = frameNumber;
		latestNackReceived = -1;
	}

	private void sendAck(byte frameNumber) {
		//create an Ack frame of a certain frame number
		//then transmit it
		
		Deque<Byte> ackToSend = new LinkedList<>();
		
		int parityVal = 0;
		
		//add Frame # and Ack representer first
		//compute number of 1's in them
		addData(ackToSend, frameNumber);
		parityVal += sumOnes(frameNumber);
		addData(ackToSend, ackIndicator);
		parityVal += sumOnes(ackIndicator);
		
		//no data to send, so just compute parity Val 
		//and append it at the start		
		if (parityVal%2 == 0) {
			ackToSend.addFirst((byte) 0);
		}
		else {
			ackToSend.addFirst((byte) 1);
		}
		
		//finally, add start and stop tags
		ackToSend.addFirst(startTag);
		ackToSend.add(stopTag);
		
		transmit(toByteArray(ackToSend));
	}
	
	private void sendNack(byte nackedFrameNumber) {
		//create a nack frame of a certain frame number
		//then transmit it
		
		Deque<Byte> nackToSend = new LinkedList<>();
		
		int parityVal = 0;
		
		//handle adding Frame # and Nack representation first
		//compute number of 1's in them
		addData(nackToSend, nackedFrameNumber);
		parityVal += sumOnes(nackedFrameNumber);
		addData(nackToSend, nackIndicator);
		parityVal += sumOnes(nackIndicator);
		
		//no data to send, so just compute parity over the frame so far
		//and append value at start		
		if (parityVal%2 == 0) {
			nackToSend.addFirst((byte) 0);
		}
		else {
			nackToSend.addFirst((byte) 1);
		}
		
		//finally, add start and stop tags
		nackToSend.addFirst(startTag);
		nackToSend.add(stopTag);
		
		transmit(toByteArray(nackToSend));
	}

	private byte[] toByteArray(Queue<Byte> data) {
		//convert a queue (or dequeue) to byte array 
		byte[] dataReceived = new byte[data.size()];
		int j = 0;
		Iterator<Byte> i = data.iterator();
		while (i.hasNext()) {
		    dataReceived[j] = i.next();
		    if (debug) {
			System.out.printf("DumbDataLinkLayer.processFrame():\tbyte[%d] = %c\n",
					  j,
					  dataReceived[j]);
		    }
		    j += 1;
		}		

		return dataReceived;
	}

}
