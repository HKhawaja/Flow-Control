// =============================================================================
// IMPORTS

import java.util.LinkedList;
import java.util.Queue;
import java.util.Iterator;
import java.lang.reflect.Constructor;
import java.lang.reflect.InvocationTargetException;
// =============================================================================



// =============================================================================
/**
 * @file   DataLinkLayer.java
 * @author Scott F. Kaplan (sfkaplan@cs.amherst.edu)
 * @date   August 2018, original September 2004
 *
 * A data link layer accepts a string of bytes, divides it into frames, adds
 * some metadata, and sends the frame via its physical layer.  Upon receiving a
 * frame, the data link layer removes the metadata, potentially performs some
 * checks on the data, and delivers the data to its client network layer.
 */
public abstract class DataLinkLayer {
// =============================================================================



    // =========================================================================
    // PUBLIC METHODS
    // =========================================================================



    // =========================================================================
    /**
     * Create the requested data link layer type and return it.
     *
     * @param  type          The subclass of which to create an instance.
     * @param  physicalLayer The physical layer by which to communicate.
     * @param  host          The host for which this layer is communicating.
     * @return The newly created data link layer.
     * @throws RuntimeException if the given type is not a valid subclass, or if
     *                          the given physical layer doesn't exist (is
     *                          <code>null</code>).
     */
    public static DataLinkLayer create (String        type,
					PhysicalLayer physicalLayer,
					Host          host) {

	if (physicalLayer == null) {
	    throw new RuntimeException("Null physical layer");
	}
	
	// Look up the class by name.
	String className           = type + "DataLinkLayer";
	Class  dataLinkLayerClass  = null;
	try {
	    dataLinkLayerClass = Class.forName(className);
	} catch (ClassNotFoundException e) {
	    throw new RuntimeException("Unknown data link layer subclass " +
				       className);
	}

	// Make one of these objects, and then see if it really is a
	// DataLinkLayer subclass.
	Object o = null;
	try {
	    o = dataLinkLayerClass.newInstance();
	} catch (InstantiationException e) {
	    throw new RuntimeException("Could not instantiate " + className);
	} catch (IllegalAccessException e) {
	    throw new RuntimeException("Could not access " + className);
	}
	DataLinkLayer dataLinkLayer = null;
	try {
	    dataLinkLayer = (DataLinkLayer)o;
	} catch (ClassCastException e) {
	    throw new RuntimeException(className +
				       " is not a subclass of DataLinkLayer");
	}


	// Register this new data link layer with the physical layer.
	dataLinkLayer.physicalLayer = physicalLayer;
	physicalLayer.register(dataLinkLayer);
	dataLinkLayer.register(host);
	
	// Create incoming buffer space.
	dataLinkLayer.bitBuffer     = new LinkedList<Boolean>();
	dataLinkLayer.receiveBuffer = new LinkedList<Byte>();
	dataLinkLayer.sendBuffer    = new LinkedList<Byte>();
	return dataLinkLayer;

    } // create ()
    // =========================================================================




    // =========================================================================
    // Allow a host to register as the client of this data link layer.
    public void register (Host client) {

	// Is there already a client registered?
	if (this.client != null) {
	    throw new RuntimeException("Attempt to double-register");
	}

	// Hold a pointer to the client.
	this.client = client;

    } // register
    // =========================================================================



    // =========================================================================
    /**
     * Send a sequence of bytes through the physical layer.  Expected to be
     * called by the client.
     *
     * @param data The sequence of bytes to send.
     */
    public void send (byte[] data) {

		// Buffer the data to send.
		bufferForSending(data);
	
		// Send until the buffer is empty.
		while (sendBuffer.peek() != null) {
		    sendNextFrame();
		}

    }
    // =========================================================================



    // =========================================================================
    /**
     * Accept an arbitrary sequence of bytes (of any length) and buffer it for
     * sending.
     *
     * @param data The sequence of bytes to buffer.
     */
    protected void bufferForSending (byte[] data) {

	// Add each byte to the sending buffer.
	for (int i = 0; data != null && i < data.length; i += 1) {
	    sendBuffer.add(data[i]);
	}
	
    } // bufferForSending ()
    // =========================================================================



    // =========================================================================
    /**
     * Extract the next frame-worth of data from the sending buffer, frame it,
     * and then send it.
     */
    protected void sendNextFrame () {

	// Extract a frame-worth of data from the sending buffer.
	int frameSize = ((sendBuffer.size() < MAX_FRAME_SIZE)
			 ? sendBuffer.size()
			 : MAX_FRAME_SIZE);
	byte[]         data = new byte[frameSize];
	Iterator<Byte>    i = sendBuffer.iterator();
	for (int j = 0; j < frameSize; j += 1) {
	    data[j] = i.next();
	    i.remove();
	}

	// Frame and transmit this chunk.
	byte[] framedData = createFrame(data);
	latestFrameSent = framedData;
	transmit(framedData);

	// Finish any bookkeeping with respect to this frame having been sent.
	finishFrameSend();

    } // sendNextFrame ()
    // =========================================================================



    // =========================================================================
    /**
     * Embed a raw sequence of bytes into a framed sequence.
     *
     * @param  data The raw sequence of bytes to be framed.
     * @return A complete frame.
     */
    abstract protected byte[] createFrame (byte[] data);
    // =========================================================================



    // =========================================================================
    /**
     * Complete the process of sending a frame.  This method may: do nothing;
     * await an acknowledgment; advance frame numbers; etc.
     */
    abstract protected void finishFrameSend ();
    // =========================================================================



    // =========================================================================
    /**
     * Transmit a sequence of bytes as bits.
     *
     * @param data The sequence of bytes to send.
     */
    protected void transmit (byte[] data) {

	for (byte b : data) {

	    // Transmit one bit at a time, most to least significant.
	    for (int i = BITS_PER_BYTE - 1; i >= 0; i -= 1) {

		// Grab the current bit...
		boolean bit = ((1 << i) & b) != 0;

		// ...and send it.
		physicalLayer.send(bit);

	    }

	}

    } // transmit ()
    // =========================================================================



    // =========================================================================
    /**
     * Deliver a bit into this layer.  Expected to be called by the physical
     * layer.  Accumulate bits into a buffer, and with each full byte received,
     * accumulate those bits into a byte buffer.  Each byte added to the buffer
     * is examined to determine whether a whole frame has been received, and if
     * so, then processed.
     *
     * @param bit The value to receive, where <code>false</code> indicates a
     *            <code>0</code>, and <code>true</code> indicates a
     *            <code>1</code>.
     */
    public void receive (boolean bit) {

	// Add the new bit to the buffer.
	bitBuffer.add(bit);

	// If this bit completes a byte, then add it to the byte buffer.
	if (bitBuffer.size() >= BITS_PER_BYTE) {

	    // Build up the byte from the bits...
	    byte newByte = 0;
	    for (int i = 0; i < BITS_PER_BYTE; i += 1) {
		bit = bitBuffer.remove();
		newByte = (byte)((newByte << 1) | (bit ? 1 : 0));
	    }

	    // ...and add it to the byte buffer.
	    receiveBuffer.add(newByte);
	    if (debug) {
		System.out.printf("DataLinkLayer.receive(): Got new byte = %c\n",
				  newByte);
	    }

	    // Attempt to process the buffered bytes as a frame.  If a complete
	    // frame is found and its contents extracted, complete receiving.
	    Queue<Byte> originalData = processFrame();
	    if (originalData != null) {
	    	finishFrameReceive(originalData);
	    }

	}

    } // receive ()
    // =========================================================================



    // =========================================================================
    /**
     * An entire frame has been received.  Complete its processing, which may
     * involve checking its correctness, responding to the sender, and/or
     * delivering the frame to the client (if correct).  Called by
     * <code>receive()</code>.
     *
     * @param data The de-tagged contents extracted from the frame.
     * @see   DataLinkLayer.receive
     */
    abstract protected void finishFrameReceive (Queue<Byte> data);
    // =========================================================================
    


    // =========================================================================
    /**
     * Determine whether the byte buffer contains a complete frame.  If so,
     * extract its contents, removing all metadata and (if applicable) checking
     * its correctness, then returning (if possible) the contained data.
     *
     * @return if possible, the extracted data from the frame; <code>null</code>
     *         otherwise.
     */
    abstract protected Queue<Byte> processFrame ();
    // =========================================================================



    // =========================================================================
    // INSTANCE DATA MEMBERS

    /** The physical layer used by this layer. */
    protected PhysicalLayer  physicalLayer;

    /** The host that is using this layer. */
    protected Host           client;

    /** The buffer of bits recently received, building up the current byte. */
    protected Queue<Boolean> bitBuffer;

    /** The buffer of bytes recently received, building up the current frame. */
    protected Queue<Byte>    receiveBuffer;

    /** The buffer of data yet to be sent. */
    protected Queue<Byte>    sendBuffer;
    
    /** Latest frame sent */
    protected byte[] latestFrameSent;
    // =========================================================================



    // =========================================================================
    // CLASS DATA MEMBERS

    /** The number of bits in a byte. */
    public static final int     BITS_PER_BYTE    = 8;

    /** The maximum number of original data bytes that a frame may contain. */
    public static final int     MAX_FRAME_SIZE   = 8;

    /** The duration of a timeout event (for flow control). */
    public static final long    TIMEOUT_INTERVAL = 250;

    /** Whether to emit debugging information. */
    public static final boolean debug            = false;
    // =========================================================================


    
// =============================================================================
} // class DataLinkLayer
// =============================================================================
