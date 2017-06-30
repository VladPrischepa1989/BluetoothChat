package ua.vladprischepa.bluetoothchat;

import android.bluetooth.BluetoothAdapter;
import android.bluetooth.BluetoothDevice;
import android.bluetooth.BluetoothServerSocket;
import android.bluetooth.BluetoothSocket;
import android.os.Bundle;
import android.os.Handler;
import android.os.Message;
import android.util.Log;

import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.util.UUID;

public class BluetoothChatManager {

    private static final String TAG = BluetoothChatManager.class.getSimpleName();

    private static final String NAME = "ChatSocket";

    public static final UUID UUID_SECURE = UUID.fromString("fs89c0d0-afbc-11de-8a39-0800200c9a66");
    public static final UUID UUID_INSECURE = UUID.fromString("89cd20d0-afbc-11de-8a39-0800200c9a66");

    public static final int STATE_NULL = 100;
    public static final int STATE_LISTENING = 101;
    public static final int STATE_CONNECTING = 102;
    public static final int STATE_CONNECTED = 103;

    // Message types sent from the BluetoothChatService Handler
    public static final int MESSAGE_STATE_CHANGE = 1;
    public static final int MESSAGE_READ = 2;
    public static final int MESSAGE_WRITE = 3;
    public static final int MESSAGE_DEVICE_NAME = 4;
    public static final int MESSAGE_TOAST = 5;

    // Key names received from the BluetoothChatService Handler
    public static final String DEVICE_NAME = "device_name";
    public static final String TOAST = "toast";


    private final BluetoothAdapter mBtAdapter;
    private final Handler mHandler;

    private ConnectionThread mConnectionThread;
    private AcceptThread mAcceptThread;
    private ConnectionFinishedThread mConnectionFinishedThread;

    private int mState;
    private int mUpdatedState;



    public BluetoothChatManager(Handler handler){
        mBtAdapter = BluetoothAdapter.getDefaultAdapter();
        mHandler = handler;
        mState = STATE_NULL;
        mUpdatedState = mState;
    }

    public synchronized int getState(){
        return mState;
    }

    private synchronized void updateState(){
        mHandler.obtainMessage(MESSAGE_STATE_CHANGE, getState(), -1).sendToTarget();
    }

    public synchronized void start(){
        // Cancel any thread attempting to make a connection
        if (mConnectionThread != null) {
            mConnectionThread.cancel();
            mConnectionThread = null;
        }

        // Cancel any thread currently running a connection
        if (mConnectionFinishedThread != null) {
            mConnectionFinishedThread.cancel();
            mConnectionFinishedThread = null;
        }

        // Start the thread to listen on a BluetoothServerSocket
        if (mAcceptThread == null) {
            mAcceptThread = new AcceptThread();
            mAcceptThread.start();
        }
        // Update UI title
        updateState();
    }

    public synchronized void connect(BluetoothDevice device) {
        Log.d(TAG, "connect to: " + device);

        // Cancel any thread attempting to make a connection
        if (mState == STATE_CONNECTING) {
            if (mConnectionThread != null) {
                mConnectionThread.cancel();
                mConnectionThread = null;
            }
        }

        // Cancel any thread currently running a connection
        if (mConnectionFinishedThread != null) {
            mConnectionFinishedThread.cancel();
            mConnectionFinishedThread = null;
        }

        // Start the thread to connect with the given device
        mConnectionThread = new ConnectionThread(device);
        mConnectionThread.start();
        // Update UI title
        updateState();
    }

    public synchronized void onConnected(BluetoothSocket socket, BluetoothDevice
            device) {

        // Cancel the thread that completed the connection
        if (mConnectionThread != null) {
            mConnectionThread.cancel();
            mConnectionThread = null;
        }

        // Cancel any thread currently running a connection
        if (mConnectionFinishedThread != null) {
            mConnectionFinishedThread.cancel();
            mConnectionFinishedThread = null;
        }

        // Cancel the accept thread because we only want to connect to one device
        if (mAcceptThread != null) {
            mAcceptThread.cancel();
            mAcceptThread = null;
        }

        // Start the thread to manage the connection and perform transmissions
        mConnectionFinishedThread = new ConnectionFinishedThread(socket);
        mConnectionFinishedThread.start();

        // Send the name of the connected device back to the UI Activity
        Message msg = mHandler.obtainMessage(MESSAGE_DEVICE_NAME);
        Bundle bundle = new Bundle();
        bundle.putString(DEVICE_NAME, device.getName());
        msg.setData(bundle);
        mHandler.sendMessage(msg);
        // Update UI title
        updateState();
    }

    public synchronized void stop() {
        Log.d(TAG, "stop");

        if (mConnectionThread != null) {
            mConnectionThread.cancel();
            mConnectionThread = null;
        }

        if (mConnectionFinishedThread != null) {
            mConnectionFinishedThread.cancel();
            mConnectionFinishedThread = null;
        }

        if (mAcceptThread != null) {
            mAcceptThread.cancel();
            mAcceptThread = null;
        }

        mState = STATE_NULL;
        // Update UI title
        updateState();
    }

    /**
     * Write to the ConnectedThread in an unsynchronized manner
     *
     * @param out The bytes to write
     * @see ConnectionFinishedThread#writeOutputStream(byte[])
     */
    public void writeOutputStream(byte[] out) {
        // Create temporary object
        ConnectionFinishedThread tmpThread;
        // Synchronize a copy of the ConnectedThread
        synchronized (this) {
            if (mState != STATE_CONNECTED) return;
            tmpThread = mConnectionFinishedThread;
        }
        // Perform the write unsynchronized
        tmpThread.writeOutputStream(out);
    }

    /**
     * Indicate that the connection attempt failed and notify the UI Activity.
     */
    private void onConnectionFailed() {
        // Send a failure message back to the Activity
        Message msg = mHandler.obtainMessage(MESSAGE_TOAST);
        Bundle bundle = new Bundle();
        bundle.putString(TOAST, "Unable to connect device");
        msg.setData(bundle);
        mHandler.sendMessage(msg);

        mState = STATE_NULL;
        // Update UI title
        updateState();

        // Start the service over to restart listening mode
        BluetoothChatManager.this.start();
    }

    /**
     * Indicate that the connection was lost and notify the UI Activity.
     */
    private void onConnectionLost() {
        // Send a failure message back to the Activity
        Message msg = mHandler.obtainMessage(MESSAGE_TOAST);
        Bundle bundle = new Bundle();
        bundle.putString(TOAST, "Device connection was lost");
        msg.setData(bundle);
        mHandler.sendMessage(msg);

        mState = STATE_NULL;
        // Update UI title
        updateState();

        // Start the service over to restart listening mode
        BluetoothChatManager.this.start();
    }

    private class ConnectionThread extends Thread{

        private BluetoothSocket mBluetoothSocket;
        private BluetoothDevice mBluetoothDevice;

        public ConnectionThread(BluetoothDevice device){
            mBluetoothDevice = device;
            try {
                mBluetoothSocket = mBluetoothDevice.createRfcommSocketToServiceRecord(UUID_SECURE);
            } catch (IOException e){
                Log.e(TAG, e.getMessage());
            }
            mState = STATE_CONNECTING;
        }

        @Override
        public void run() {
            mBtAdapter.cancelDiscovery();

            try {
                mBluetoothSocket.connect();
            } catch (IOException e){
                try {
                    mBluetoothSocket.close();
                } catch (IOException e1){
                    Log.e(TAG, e.getMessage());
                }
                onConnectionFailed();
                return;
            }

            synchronized (BluetoothChatManager.this){
                mConnectionThread = null;
            }

            onConnected(mBluetoothSocket, mBluetoothDevice);
        }

        public void cancel(){
            try {
                mBluetoothSocket.close();
            } catch (IOException e){
                Log.e(TAG, e.getMessage());
            }
        }
    }

    private class ConnectionFinishedThread extends Thread{

        private final BluetoothSocket mSocket;
        private final InputStream mInStream;
        private final OutputStream mOutStream;

        public ConnectionFinishedThread(BluetoothSocket socket){
            mSocket = socket;
            InputStream tmpInputStream = null;
            OutputStream tmpOutputStream = null;
            try {
                tmpInputStream = mSocket.getInputStream();
                tmpOutputStream = mSocket.getOutputStream();
            } catch (IOException e){
                Log.e(TAG, e.getMessage());
            }
            mInStream = tmpInputStream;
            mOutStream = tmpOutputStream;
            mState = STATE_CONNECTED;
        }

        @Override
        public void run() {
            byte[] buffer = new byte[1024];
            int bytes;

            while (mState == STATE_CONNECTED){
                try {
                    bytes = mInStream.read(buffer);
                    mHandler.obtainMessage(MESSAGE_READ, bytes, -1, buffer).sendToTarget();
                } catch (IOException e){
                    Log.e(TAG, e.getMessage() );
                    onConnectionLost();
                }
            }
        }

        public void writeOutputStream(byte[] buffer){
            try{
                mOutStream.write(buffer);
                mHandler.obtainMessage(MESSAGE_WRITE, -1, -1, buffer).sendToTarget();
            } catch (IOException e){
                Log.e(TAG, e.getMessage());
            }
        }

        public void cancel(){
            try {
                mSocket.close();
            } catch (IOException e){
                Log.e(TAG, e.getMessage());
            }
        }
    }

    private class AcceptThread extends Thread{

        private BluetoothServerSocket mServerSocket = null;

        public AcceptThread(){
            try {
                mServerSocket = mBtAdapter.listenUsingRfcommWithServiceRecord(NAME, UUID_SECURE);
            } catch (IOException e){
                Log.e(TAG, e.getMessage());
            }

            mState = STATE_LISTENING;
        }

        @Override
        public void run() {
            BluetoothSocket socket = null;
            while (mState != STATE_CONNECTED){
                try{
                    socket = mServerSocket.accept();
                } catch (IOException e){
                    Log.e(TAG, e.getMessage() );
                    break;
                }

                if (socket != null){
                    synchronized (BluetoothChatManager.this){
                        switch (mState){
                            case STATE_LISTENING:
                            case STATE_CONNECTING:
                                onConnected(socket, socket.getRemoteDevice());
                                break;
                            case STATE_NULL:
                            case STATE_CONNECTED:
                                try {
                                    socket.close();
                                } catch (IOException e){
                                    Log.e(TAG, e.getMessage() );
                                }
                                break;
                        }
                    }
                }
            }
        }

        public void cancel(){
            try{
                mServerSocket.close();
            } catch (IOException e){
                Log.e(TAG, e.getMessage() );
            }
        }
    }
}
