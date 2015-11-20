package com.d3engineering.ThermalCameraViewer;

import java.io.BufferedReader;
import java.io.InputStreamReader;
import java.io.OutputStream;
import java.math.BigInteger;
import java.net.InetAddress;
import java.net.Socket;
import java.net.UnknownHostException;
import java.nio.ByteBuffer;
import java.util.Arrays;
import java.util.Collections;
import java.util.List;

import android.app.Activity;
import android.content.Intent;
import android.net.wifi.WifiManager;
import android.os.Bundle;
import android.os.Handler;
import android.util.Log;
import android.view.Menu;
import android.view.MenuItem;
import android.view.SurfaceHolder;
import android.view.SurfaceView;
import android.view.View.OnClickListener;
import android.view.View;
import android.widget.Button;
import android.widget.ImageButton;
import android.widget.Spinner;
import android.widget.TextView;
import android.widget.Toast;

import com.gstreamer.GStreamer;


public class MainActivity extends Activity implements SurfaceHolder.Callback {
    private native void nativeInit();     // Initialize native code, build pipeline, etc
    private native void nativeFinalize(); // Destroy pipeline and shutdown native code
    private native void nativePlay();     // Set pipeline to PLAYING
    private native void nativePause();    // Set pipeline to PAUSED
    private static native boolean nativeClassInit(); // Initialize native class: cache Method IDs for callbacks
    private native void nativeSurfaceInit(Object surface);
    private native void nativeSurfaceFinalize();
    private long native_custom_data;      // Native code will use this to keep private data

    private boolean is_playing_desired;   // Whether the user asked to go to PLAYING

    Handler updateTCPHandler;
    Thread	tcpThread = null;

    private Button btnSendReticle;
    private Spinner cbReticle;
    private TCPClient mTcpClient;
    
    final Activity mainActivity = this;
    
    public static Byte[] ConvertPrimitiveToByte(byte[] bytesPrim) {
        Byte[] bytes = new Byte[bytesPrim.length];

        int i = 0;
        for (byte b : bytesPrim) bytes[i++] = b; // Autoboxing

        return bytes;
    }

    public static byte[] ConvertByteToPrimitives(List<Byte> byteList)
    {
        byte[] bytes = new byte[byteList.size()];

        for(int i = 0; i < byteList.size(); i++) {
            bytes[i] = byteList.get(i);
        }

        return bytes;
    }
    
	@Override
	protected void onCreate(Bundle savedInstanceState) {
		super.onCreate(savedInstanceState);
		
		try {
            GStreamer.init(this);
        } catch (Exception e) {
            Toast.makeText(this, e.getMessage(), Toast.LENGTH_LONG).show();
            finish(); 
            return;
        }
		
		setContentView(R.layout.activity_main);

		
		ImageButton play = (ImageButton) this.findViewById(R.id.button_play);
        play.setOnClickListener(new OnClickListener() {
            public void onClick(View v) {
            	
            	WifiManager wm = (WifiManager) getSystemService(WIFI_SERVICE);
            	//String ip = Formatter.formatIpAddress(wm.getConnectionInfo().getIpAddress());
            	Byte[] myIPAddress = ConvertPrimitiveToByte(BigInteger.valueOf(wm.getConnectionInfo().getIpAddress()).toByteArray());
            	List<Byte> byteList = Arrays.asList(myIPAddress); 
            	
            	// you must reverse the byte array before conversion. Use Apache's commons library
            	Collections.reverse(byteList); 
            	
            	try{
            		InetAddress myInetIP = InetAddress.getByAddress(ConvertByteToPrimitives(byteList));
            		String myIP = myInetIP.getHostAddress();
            		//sends the message to the server
                    if (mTcpClient != null) {
                        mTcpClient.sendMessage("IP="+myIP);
                    }
                    
            		is_playing_desired = true;
            		nativePlay();
            		Toast.makeText(getApplicationContext(), "Starting Camera Stream", Toast.LENGTH_LONG).show(); 
            	}catch(UnknownHostException uhe){
            		is_playing_desired = false;
                    Toast.makeText(getApplicationContext(), uhe.getMessage(), Toast.LENGTH_LONG).show();            		
            	}
            	
            }
        });

        ImageButton pause = (ImageButton) this.findViewById(R.id.button_stop);
        pause.setOnClickListener(new OnClickListener() {
            public void onClick(View v) {
                is_playing_desired = false;
                nativePause();
            }
        });

        SurfaceView sv = (SurfaceView) this.findViewById(R.id.surface_video);
        SurfaceHolder sh = sv.getHolder();
        sh.addCallback(this);

        if (savedInstanceState != null) {
            is_playing_desired = savedInstanceState.getBoolean("playing");
            Log.i ("GStreamer", "Activity created. Saved state is playing:" + is_playing_desired);
        } else {
            is_playing_desired = false;
            Log.i ("GStreamer", "Activity created. There is no saved state, playing: false");
        }

        // COMMAND BUTTON
        btnSendReticle = (Button)findViewById(R.id.btnReticleSend);
        btnSendReticle.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                // get selected reticle id and send to stack;
            	int iReticle = cbReticle.getSelectedItemPosition();
                Toast.makeText(getBaseContext(), "Sending Reticle Command " + iReticle, Toast.LENGTH_SHORT).show();
                ByteBuffer reticlePacket = ByteBuffer.allocate(8);
                // need to wrap this in a class
                // length
                reticlePacket.put(0,(byte)0x08);
                reticlePacket.put(1,(byte)0x00);
             
                // command
                reticlePacket.put(2,(byte)0x01);
                reticlePacket.put(3,(byte)0x00);

                // reticle
                reticlePacket.put(4,(byte)iReticle);
                reticlePacket.put(5,(byte)0x00);

                // checksum
                reticlePacket.put(6,(byte)0x00);
                reticlePacket.put(7,(byte)0x00);
                
                if (mTcpClient != null) {
                    mTcpClient.sendPacket(reticlePacket.array());
                }
            }
        });

        cbReticle = (Spinner)findViewById(R.id.cbReticle);
        
        // start TCP 
        updateTCPHandler = new Handler();
        mTcpClient = new TCPClient();
        this.tcpThread = new Thread(mTcpClient);
        this.tcpThread.start();
        //new connectTask().execute("");
        
        // Start with disabled buttons, until native code is initialized
        this.findViewById(R.id.button_play).setEnabled(false);
        this.findViewById(R.id.button_stop).setEnabled(false);

        nativeInit();
		

	}
	
    protected void onSaveInstanceState (Bundle outState) {
        Log.d ("GStreamer", "Saving state, playing:" + is_playing_desired);
        outState.putBoolean("playing", is_playing_desired);
    }

    @Override
    protected void onStop() {
		Log.d("MainActivity", "OnDestroy called");
    	if (mTcpClient != null) {
    		Log.d("MainActivity", "Sending OFF Message");
            mTcpClient.sendMessage("OFFCAM");
        }
        super.onStop();
    }

    
	@Override
    protected void onDestroy() {
		Log.d("MainActivity", "OnDestroy called");
    	if (mTcpClient != null) {
    		Log.d("MainActivity", "Sending OFF Message");
            mTcpClient.sendMessage("OFFCAM");
        }
        nativeFinalize();
        super.onDestroy();
    }

    // Called from native code. This sets the content of the TextView from the UI thread.
    private void setMessage(final String message) {
        final TextView tv = (TextView) this.findViewById(R.id.textview_message);
        runOnUiThread (new Runnable() {
          public void run() {
            tv.setText(message);
          }
        });
    }

    // Called from native code. Native code calls this once it has created its pipeline and
    // the main loop is running, so it is ready to accept commands.
    private void onGStreamerInitialized () {
        Log.i ("GStreamer", "Gst initialized. Restoring state, playing:" + is_playing_desired);
        // Restore previous playing state
        if (is_playing_desired) {
            nativePlay();
        } else {
            nativePause();
        }

        // Re-enable buttons, now that GStreamer is initialized
        final Activity activity = this;
        runOnUiThread(new Runnable() {
            public void run() {
                activity.findViewById(R.id.button_play).setEnabled(true);
                activity.findViewById(R.id.button_stop).setEnabled(true);
            }
        });
    }
    
	@Override
	public boolean onCreateOptionsMenu(Menu menu) {
		// Inflate the menu; this adds items to the action bar if it is present.
		getMenuInflater().inflate(R.menu.main, menu);
		return true;
	}

	@Override
	public boolean onOptionsItemSelected(MenuItem item) {
		// Handle action bar item clicks here. The action bar will
		// automatically handle clicks on the Home/Up button, so long
		// as you specify a parent activity in AndroidManifest.xml.
		int id = item.getItemId();
        
		if (id == R.id.action_settings) {
            Intent intent = new Intent(this, SettingsActivity.class);
            startActivity(intent);
            return true;
        }
        
		return super.onOptionsItemSelected(item);
	}
	
    static {
        System.loadLibrary("gstreamer_android");
        System.loadLibrary("L3ThermalCameraViewer");
        nativeClassInit();
    }

    public void surfaceChanged(SurfaceHolder holder, int format, int width,
            int height) {
        Log.d("GStreamer", "Surface changed to format " + format + " width "
                + width + " height " + height);
        nativeSurfaceInit (holder.getSurface());
    }

    public void surfaceCreated(SurfaceHolder holder) {
        Log.d("GStreamer", "Surface created: " + holder.getSurface());
    }

    public void surfaceDestroyed(SurfaceHolder holder) {
        Log.d("GStreamer", "Surface destroyed");
        nativeSurfaceFinalize ();
    }

    class updateUIThread implements Runnable {
    	private String msg;
    	public updateUIThread(String str){
    		this.msg = str;
    	}
    	
    	@Override
    	public void run(){
            Toast.makeText(getApplicationContext(), msg, Toast.LENGTH_LONG).show(); 
    	}
    }
    

    //Declare the interface. The method messageReceived(String message) will must be implemented in the MyActivity
    //class at on asynckTask doInBackground
    public interface OnMessageReceived {
        public void messageReceived(String message);
    }
    
	public class TCPClient implements Runnable {
	    private String serverMessage;
	    public static final String SERVERIP = "10.0.2.1"; //D3 Stack IP Address
	    public static final int SERVERPORT = 8024;
	    private boolean mRun = false;
	    OutputStream out;
	    BufferedReader in;

	    /**
	     *  Constructor of the class. OnMessagedReceived listens for the messages received from server
	     */
	    public TCPClient() {
	    }
	    
	    /**
	     * Sends the message entered by client to the server
	     * @param message text entered by client
	     */
	    public void sendMessage(String message){
	        if (out != null) {
	        	int length = message.getBytes().length;
	        	try{
	        		out.write(message.getBytes(), 0, length);
	        		out.flush();
	        	}catch(Exception ex){
	        		updateTCPHandler.post(new updateUIThread("Error Sending Packet"));
	        	}
	        }else{
	        	updateTCPHandler.post(new updateUIThread("NULL ouput stream"));
	        }
	        
	    }

	    public void sendPacket(byte[] packet){
	        if (out != null) {
	        	try{
	        		out.write(packet, 0 , packet.length);
	        		out.flush();
	        	}catch(Exception ex){
	        		updateTCPHandler.post(new updateUIThread("Error Sending Packet"));
	        	}
	        }else{
	        	updateTCPHandler.post(new updateUIThread("NULL ouput stream"));
	        }
	    }
	    
	    public void stopClient(){
	        mRun = false;
	    }

	    public void run() {

	        mRun = true;

	        try {
	            //here you must put your computer's IP address.
	            InetAddress serverAddr = InetAddress.getByName(SERVERIP);

	            Log.e("TCP Client", "C: Connecting...");

	            //create a socket to make the connection with the server
	            Socket socket = new Socket(serverAddr, SERVERPORT);

	            try {

	                //send the message to the server
	                out = socket.getOutputStream();

	                Log.d("TCP Client", "C: Sent.");

	                Log.d("TCP Client", "C: Done.");

	                //receive the message which the server sends back
	                in = new BufferedReader(new InputStreamReader(socket.getInputStream()));

	                Log.d("TCP Client", "C: waiting for response from server.");
	                //in this while the client listens for the messages sent by the server
	                while (mRun) {
	                    serverMessage = in.readLine();
	                    Log.d("TCP Client", "C: response received.");

	                    if (serverMessage != null) {
	                        //call the method messageReceived from MyActivity class

	                        Log.d("TCP Client", "C: sending response to Main Activity");
	                        updateTCPHandler.post(new updateUIThread("Sent Message to ICD"));
	                        
	                    }
	                    serverMessage = null;

	                }

	                Log.e("RESPONSE FROM SERVER", "S: Received Message: '" + serverMessage + "'");

	            } catch (Exception e) {

	                Log.e("TCP", "S: Error", e);

	            } finally {
	                //the socket must be closed. It is not possible to reconnect to this socket
	                // after it is closed, which means a new socket instance has to be created.
	                socket.close();
	            }

	        } catch (Exception e) {

	            Log.e("TCP", "C: Error", e);

	        }

	    }

	}
}
