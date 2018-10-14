package com.radix.nine.libusbforandroid;

import android.app.PendingIntent;
import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.content.IntentFilter;
import android.hardware.usb.UsbConstants;
import android.hardware.usb.UsbDevice;
import android.hardware.usb.UsbDeviceConnection;
import android.hardware.usb.UsbEndpoint;
import android.hardware.usb.UsbInterface;
import android.hardware.usb.UsbManager;
import android.os.AsyncTask;
import android.os.Bundle;
import android.os.Handler;
import android.os.Message;
import android.os.SystemClock;
import android.support.v7.app.AppCompatActivity;
import android.util.Log;
import android.view.View;
import android.widget.EditText;
import android.widget.ProgressBar;
import android.widget.TextView;
import android.widget.Toast;

import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

public class MainActivity extends AppCompatActivity {

    private static final int MESSAGE_REFRESH = 101;
    private static final long REFRESH_TIMEOUT_MILLIS = 3000;
    private static final String ACTION_USB_PERMISSION = "com.android.example.USB_PERMISSION";
    private final String TAG = MainActivity.class.getSimpleName();
    private final ExecutorService mExecutor = Executors.newSingleThreadExecutor();
    private final BroadcastReceiver mUsbReceiver = new BroadcastReceiver() {
        public void onReceive(Context context, Intent intent) {
            String action = intent.getAction();
            if (ACTION_USB_PERMISSION.equals(action)) {
                synchronized (this) {
                    UsbDevice device = intent.getParcelableExtra(UsbManager.EXTRA_DEVICE);
                    if (intent.getBooleanExtra(UsbManager.EXTRA_PERMISSION_GRANTED, false)) {
                        if (device != null) {
                            //not used yet
                        }
                    } else {
                        Log.i(TAG, "permission denied for device " + device);
                    }
                }
            }
        }
    };
    private UsbManager mUsbManager;
    private UsbDevice mUsbDevice;
    private UsbInterface mUsbInterface;
    private UsbEndpoint mInEndpoint;
    private UsbEndpoint mOutEndpoint;
    private UsbDeviceConnection mConnection;
    private InputOutputManager mInputOutputManager;
    private TextView mProgressBarTitle;
    private ProgressBar mProgressBar;
    private TextView mDumpTextView;
    private NonFocusingScrollView mScrollView;
    InputOutputManager.Listener mListener = new InputOutputManager.Listener() {
        @Override
        public void onNewData(final byte[] data) {
            MainActivity.this.runOnUiThread(new Runnable() {
                @Override
                public void run() {
                    MainActivity.this.updateReceivedData(data);
                }
            });
        }

        @Override
        public void onRunError(Exception e) {
            mHandler.sendEmptyMessage(MESSAGE_REFRESH);
            Log.i("InputOutputManager", "Runner stopped.");
        }
    };
    private final Handler mHandler = new Handler() {
        @Override
        public void handleMessage(Message msg) {
            switch (msg.what) {
                case MESSAGE_REFRESH:
                    refreshDevice();
                    mHandler.sendEmptyMessageDelayed(MESSAGE_REFRESH, REFRESH_TIMEOUT_MILLIS);
                    break;
                default:
                    super.handleMessage(msg);
                    break;
            }
        }

    };

    private void refreshDevice() {
        showProgressBar();
        new AsyncTask<UsbManager, Void, UsbDevice>() {
            @Override
            protected UsbDevice doInBackground(UsbManager... usbManagers) {
                Log.d(TAG, "Refreshing device list ...");
                SystemClock.sleep(1000);
                for (UsbManager usbManager : usbManagers) {
                    if (usbManager != null) {
                        return findDevice(usbManager);
                    }
                }
                return null;
            }

            @Override
            protected void onPostExecute(UsbDevice result) {
                hideProgressBar();
                if (result != null) {
                    mUsbDevice = result;
                    mProgressBarTitle.setText(String.format("device found"));
                    Log.d(TAG, "Done refreshing, device found.");
                    initUsbDevice();
                    mHandler.removeMessages(MESSAGE_REFRESH);
                } else {
                    mProgressBarTitle.setText(String.format("nothing"));
                }
            }

        }.execute(mUsbManager);
    }

    UsbDevice findDevice(UsbManager usbManager) {
        for (UsbDevice usbDevice : usbManager.getDeviceList().values()) {
            if (usbDevice.getDeviceClass() == UsbConstants.USB_CLASS_PER_INTERFACE) {
                return usbDevice;
            } else {
                UsbInterface usbInterface = findInterface(usbDevice);
                if (usbInterface != null) return usbDevice;
            }
        }
        return null;
    }

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_main);

        mProgressBar = (ProgressBar) findViewById(R.id.progressBar);
        mProgressBarTitle = (TextView) findViewById(R.id.progressBarTitle);
        mDumpTextView = (TextView) findViewById(R.id.consoleText);
        mScrollView = (NonFocusingScrollView) findViewById(R.id.demoScroller);
        mScrollView.setSmoothScrollingEnabled(true);

        mUsbManager = (UsbManager) getSystemService(Context.USB_SERVICE);
        mHandler.sendEmptyMessage(MESSAGE_REFRESH);
    }

    private void initUsbDevice() {
        PendingIntent mPermissionIntent = PendingIntent.getBroadcast(this, 0, new Intent(ACTION_USB_PERMISSION), 0);
        IntentFilter filter = new IntentFilter(ACTION_USB_PERMISSION);
        registerReceiver(mUsbReceiver, filter);
        mUsbManager.requestPermission(mUsbDevice, mPermissionIntent);

        mUsbInterface = findInterface(mUsbDevice);

        for (int nEp = 0; nEp < mUsbInterface.getEndpointCount(); nEp++) {
            UsbEndpoint tmpEndpoint = mUsbInterface.getEndpoint(nEp);
            if (tmpEndpoint.getType() != UsbConstants.USB_ENDPOINT_XFER_BULK) continue;

            if ((mOutEndpoint == null)
                    && (tmpEndpoint.getDirection() == UsbConstants.USB_DIR_OUT)) {
                mOutEndpoint = tmpEndpoint;
            } else if ((mInEndpoint == null)
                    && (tmpEndpoint.getDirection() == UsbConstants.USB_DIR_IN)) {
                mInEndpoint = tmpEndpoint;
            }
        }
        if (mOutEndpoint == null) {
            Toast.makeText(this, "no endpoints", Toast.LENGTH_LONG).show();
        }
        mConnection = mUsbManager.openDevice(mUsbDevice);
        if (mConnection == null) {
            Toast.makeText(this, "can't open device", Toast.LENGTH_SHORT).show();
            return;
        }
        mConnection.claimInterface(mUsbInterface, true);
        startIoManager();
    }

    private void stopIoManager() {
        if (mInputOutputManager != null) {
            Log.i(TAG, "Stopping io manager ..");
            mInputOutputManager.stop();
            mInputOutputManager = null;
        }
    }

    private void startIoManager() {
        if (mConnection != null) {
            Log.i(TAG, "Starting io manager ..");
            mInputOutputManager = new InputOutputManager(mInEndpoint, mOutEndpoint, mConnection, mListener);
            mExecutor.submit(mInputOutputManager);
        } else Toast.makeText(this, "mConnection == null", Toast.LENGTH_LONG).show();
    }

    @Override
    protected void onDestroy() {
        super.onDestroy();
        if (mUsbManager != null) {
            stopIoManager();
            mConnection.close();
        }
    }

    private void updateReceivedData(byte[] data) {
        final String message = "Read " + data.length + " bytes: \n"
                + HexDump.dumpHexString(data) + "\n\n";
        mDumpTextView.append(message);
        mScrollView.post(new Runnable() {
            public void run() {
                mScrollView.fullScroll(View.FOCUS_DOWN);
            }
        });
    }

    public void onClickButtonSend(View view) {
        EditText textTransmitView = (EditText) findViewById(R.id.textTransmit);
        byte[] data;
        data = new byte[]{0x00, 0x00, 0x00, 0x00, 0x00, 0x02, 0x01, 0x09, 0x00, 0x04, 0x00, 0x0b, 0x00, 0x00, 0x00, 0x00};
        if (mInputOutputManager == null) {
            Toast.makeText(this, "mInputOutputManager == null", Toast.LENGTH_LONG).show();
            return;
        }
        try {
            //data = textTransmitView.getText().toString().getBytes();
            mInputOutputManager.writeAsync(data);
        } catch (Exception e) {
            Toast.makeText(this, e.getMessage(), Toast.LENGTH_LONG).show();
        }
    }

    UsbInterface findInterface(UsbDevice usbDevice) {
        for (int nIf = 0; nIf < usbDevice.getInterfaceCount(); nIf++) {
            UsbInterface usbInterface = usbDevice.getInterface(nIf);
            if (usbInterface.getInterfaceClass() == UsbConstants.USB_CLASS_PER_INTERFACE) {
                return usbInterface;
            }
        }
        return null;
    }

    private void showProgressBar() {
        mProgressBar.setVisibility(View.VISIBLE);
        mProgressBarTitle.setText(R.string.refreshing);
    }

    private void hideProgressBar() {
        mProgressBar.setVisibility(View.INVISIBLE);
    }
}
