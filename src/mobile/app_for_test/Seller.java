package mobile.app_for_test;

import java.util.Random;

import android.support.v7.app.ActionBarActivity;
import android.content.Intent;
import android.os.Bundle;
import android.os.Handler;
import android.os.HandlerThread;
import android.util.Log;
import android.view.KeyEvent;
import android.view.Menu;
import android.view.MenuItem;
import android.view.View;
import android.widget.TextView;


public class Seller extends ActionBarActivity {
	
	private static final String sellerTAG = "Seller";
	
	private Thread socketThread;
    private Handler socketHandler = new Handler();
    
	private TextView textview = null;
	
    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_seller);
        
        textview = (TextView) findViewById(R.id.text_ip_seller);
    }
    
    @Override
    public boolean onCreateOptionsMenu(Menu menu) {
        // Inflate the menu; this adds items to the action bar if it is present.
        getMenuInflater().inflate(R.menu.seller, menu);
        return true;
    }

    @Override
    public boolean onOptionsItemSelected(MenuItem item) {
        // Handle action bar item clicks here. The action bar will
        // automatically handle clicks on the Home/Up button, so long
        // as you specify a parent activity in AndroidManifest.xml.
        int id = item.getItemId();
        if (id == R.id.action_settings) {
            return true;
        }
        return super.onOptionsItemSelected(item);
    }
    
    @Override
    public boolean onKeyDown(int KeyCode, KeyEvent event) {
    	switch(KeyCode)
    	{
    		case KeyEvent.KEYCODE_BACK:
    			moveTaskToBack(false);
    		case KeyEvent.KEYCODE_HOME:
    			moveTaskToBack(false);
    		case KeyEvent.KEYCODE_MENU:
    			break;
    	}
    	return super.onKeyDown(KeyCode, event);
    }
    
    public void onClick1(View view)
	{
		//String newtext = "127.0.0.1: ";
		//int randint = new Random().nextInt(10);
		//newtext = newtext + Integer.toString(randint);
		//textview.setText(newtext);
    	//socketThread = new Thread("SellerThread");
        //socketThread.run();
        socketHandler.post(waitForVpnConnection);
	}
    
    Runnable waitForVpnConnection = new Runnable() {
        public void run() {
            Log.i(sellerTAG, "beginning waiting ...");
            //try {
                
            //} catch (Exception e) {
                //Log.e(sellerTAG, "Got " + e.toString());
            //}
        }
    };
}
