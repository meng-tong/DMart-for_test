package mobile.app_for_test;

import android.support.v7.app.ActionBarActivity;
import android.content.Intent;
import android.os.Bundle;
import android.view.Menu;
import android.view.MenuItem;
import android.view.View;
import android.widget.EditText;


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
		// Inflate the menu; this adds items to the action bar if it is present.
		getMenuInflater().inflate(R.menu.buyer, menu);
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
			Intent intent = new Intent(this, BuyerService.class)
					.putExtra(prefix+".ADDRESS", edittext_content);
			startService(intent);
		}
	}
}
