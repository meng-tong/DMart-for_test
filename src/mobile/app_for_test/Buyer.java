package mobile.app_for_test;

import android.support.v7.app.ActionBarActivity;
import android.content.Intent;
import android.os.Bundle;
import android.util.Log;
import android.view.Gravity;
import android.view.KeyEvent;
import android.view.Menu;
import android.view.MenuItem;
import android.view.View;
import android.widget.EditText;
import android.widget.Toast;


public class Buyer extends ActionBarActivity {
	private EditText edittext = null;

	@Override
	protected void onCreate(Bundle savedInstanceState) {
		super.onCreate(savedInstanceState);
		setContentView(R.layout.activity_buyer);
		
		edittext = (EditText)findViewById(R.id.text_ip);
	}

	@Override
	public boolean onCreateOptionsMenu(Menu menu) {
		menu.add(Menu.NONE, 1, 1, "Back");
		menu.add(Menu.NONE, 2, 2, "Quit");
		return true;
	}

	@Override
	public boolean onOptionsItemSelected(MenuItem item) {
		int id = item.getItemId();
		switch(id) {
			case 1:
				final Intent intent = new Intent(Buyer.this, Main.class);
				new Thread(new Runnable() {
		            public void run() {
		            	startActivity(intent);
		        		finish();
		            }
		        }).start();
				break;
			case 2:
				//TODO: add Quit operations
				break;
		}
		return true;
	}
	
	@Override
    public boolean onKeyDown(int KeyCode, KeyEvent event) {
    	switch(KeyCode)
    	{
    		case KeyEvent.KEYCODE_BACK:
    			moveTaskToBack(true);
    		case KeyEvent.KEYCODE_HOME:
    			moveTaskToBack(true);
    		case KeyEvent.KEYCODE_MENU:
    			break;
    	}
    	return super.onKeyDown(KeyCode, event);
    }
	
	public void onClick1(View view)
	{
		Intent intent = BuyerService.prepare(this);
		if(intent != null)
		{
			startActivityForResult(intent, 0);
		} else
		{
			onActivityResult(0, RESULT_OK, null);
		}
	}
	
	protected void onActivityResult(int request, int result, Intent data)
	{
		if(result == RESULT_OK)
		{
			/*FIXME: whether necessary for startActivityForResult()?*/
			String prefix = getPackageName();
			
			String edittext_content = edittext.getText().toString();
			if(edittext_content.length()==0) {edittext_content = "NULL";}
			
			if(false&&!HelperFunc.isIP(edittext_content)) {
				Toast.makeText(getApplicationContext(),
						"Please Input the Correct IP Address!", Toast.LENGTH_LONG).show();
			} else {
				Intent intent = new Intent(this, BuyerService.class)
						.putExtra(prefix+".serverADDR", edittext_content);
				startService(intent);
			}
		}
	}
}
