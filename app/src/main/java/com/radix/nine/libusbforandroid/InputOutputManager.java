package com.radix.nine.libusbforandroid;

import android.hardware.usb.UsbDeviceConnection;
import android.hardware.usb.UsbEndpoint;
import android.util.Log;

import java.io.IOException;
import java.nio.ByteBuffer;
import java.util.Arrays;

public class InputOutputManager implements Runnable {

    private static final int READ_TIMEOUT = 1000;
    private static final int WRITE_TIMEOUT = 1000;
    private static final int BUFSIZ = 4096;
    private final ByteBuffer mReadBuffer = ByteBuffer.allocate(BUFSIZ);
    private final ByteBuffer mWriteBuffer = ByteBuffer.allocate(BUFSIZ);

    private final String TAG = InputOutputManager.class.getSimpleName();

    private Listener mListener;
    private UsbEndpoint mInEndpoint;
    private UsbEndpoint mOutEndpoint;
    private UsbDeviceConnection mConnection;

    private State mState = State.STOPPED;

    public InputOutputManager(UsbEndpoint mInEndpoint, UsbEndpoint mOutEndpoint, UsbDeviceConnection mConnection, Listener mListener) {
        this.mListener = mListener;
        this.mInEndpoint = mInEndpoint;
        this.mOutEndpoint = mOutEndpoint;
        this.mConnection = mConnection;
    }

    @Override
    public void run() {
        synchronized (this) {
            if (getState() != State.STOPPED) {
                throw new IllegalStateException("Already running.");
            }
            mState = State.RUNNING;
        }

        Log.i(TAG, "Running ..");
        try {
            while (true) {
                if (getState() != State.RUNNING) {
                    Log.i(TAG, "Stopping mState=" + getState());
                    break;
                }
                step();
            }
        } catch (Exception e) {
            Log.w(TAG, "Run ending due to exception: " + e.getMessage(), e);
            final Listener listener = getListener();
            if (listener != null) {
                listener.onRunError(e);
            }
        } finally {
            synchronized (this) {
                mState = State.STOPPED;
                Log.i(TAG, "Stopped.");
            }
        }
    }

    private void step() throws IOException {
        // Handle incoming data.
        int len = read(mReadBuffer.array());
        if (len > 0) {
            final Listener listener = getListener();
            if (listener != null) {
                final byte[] data = new byte[len];
                mReadBuffer.get(data, 0, len);
                listener.onNewData(data);
            }
            mReadBuffer.clear();
        }

        // Handle outgoing data.
        byte[] outBuff = null;
        synchronized (mWriteBuffer) {
            len = mWriteBuffer.position();
            if (len > 0) {
                outBuff = new byte[len];
                mWriteBuffer.rewind();
                mWriteBuffer.get(outBuff, 0, len);
                mWriteBuffer.clear();
            }
        }
        if (outBuff != null) {
            write(outBuff);
        }
    }

    public synchronized Listener getListener() {
        return mListener;
    }

    public synchronized void setListener(Listener listener) {
        mListener = listener;
    }

    public synchronized void stop() {
        if (getState() == State.RUNNING) {
            Log.i(TAG, "Stop requested");
            mState = State.STOPPING;
        }
    }

    private synchronized State getState() {
        return mState;
    }

    public void writeAsync(byte[] data) {
        synchronized (mWriteBuffer) {
            mWriteBuffer.put(data);
        }
    }

    public int read(final byte[] data) throws IOException {
        int size = Math.min(data.length, mInEndpoint.getMaxPacketSize());
        return mConnection.bulkTransfer(mInEndpoint, data, size, READ_TIMEOUT);
    }

    public int write(final byte[] data) {
        int length = data.length;
        int offset = 0;

        while (offset < length) {
            int size = Math.min(length - offset, mInEndpoint.getMaxPacketSize());
            int bytesWritten = mConnection.bulkTransfer(mOutEndpoint,
                    Arrays.copyOfRange(data, offset, offset + size), size, WRITE_TIMEOUT);

            if (bytesWritten <= 0) {
                Log.i(TAG, "nothing write");
                //Toast.makeText(this, "nothing write", Toast.LENGTH_LONG).show();
            }
            offset += bytesWritten;
        }
        return offset;
    }

    private enum State {
        STOPPED,
        RUNNING,
        STOPPING
    }

    public interface Listener {
        public void onNewData(byte[] data);

        public void onRunError(Exception e);
    }
}
