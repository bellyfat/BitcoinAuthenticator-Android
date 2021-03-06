package org.bitcoin.authenticator;

import java.io.IOException;
import java.net.Socket;
import java.util.ArrayList;
import java.util.Set;

import javax.crypto.SecretKey;

import org.bitcoin.authenticator.ConfirmTxDialog.TxDialogResponse;
import org.bitcoin.authenticator.AuthenticatorPreferences.BAPreferences;
import org.bitcoin.authenticator.Events.GlobalEvents;
import org.bitcoin.authenticator.GcmUtil.GcmIntentService;
import org.bitcoin.authenticator.GcmUtil.ProcessGCMRequest;
import org.bitcoin.authenticator.dialogs.BAAlertDialogBase;
import org.bitcoin.authenticator.dialogs.BAAlertDialogBase.DeleteOnClickListener;
import org.bitcoin.authenticator.dialogs.BAAlertDialogBase.SingleInputOnClickListener;
import org.bitcoin.authenticator.dialogs.BADeleteDialog;
import org.bitcoin.authenticator.dialogs.BAPopupMenu;
import org.bitcoin.authenticator.dialogs.BASingleInputDialog;
import org.bitcoin.authenticator.net.Message;
import org.bitcoin.authenticator.net.Connection.CannotConnectToWalletException;
import org.bitcoin.authenticator.net.Message.CouldNotSendRequestIDException;
import org.json.JSONException;
import org.json.JSONObject;
import org.json.JSONArray;

import android.os.AsyncTask;
import android.os.Bundle;
import android.app.Activity;
import android.app.AlertDialog;
import android.content.Context;
import android.content.DialogInterface;
import android.content.Intent;
import android.content.SharedPreferences;
import android.graphics.Color;
import android.graphics.drawable.ColorDrawable;
import android.support.v4.widget.SwipeRefreshLayout;
import android.support.v4.widget.SwipeRefreshLayout.OnRefreshListener;
import android.text.Html;
import android.view.ContextMenu;
import android.view.ContextMenu.ContextMenuInfo;
import android.view.KeyEvent;
import android.view.LayoutInflater;
import android.view.Menu;
import android.view.MenuItem;
import android.view.View;
import android.view.ViewGroup;
import android.view.Window;
import android.view.WindowManager;
import android.widget.AdapterView;
import android.widget.AdapterView.AdapterContextMenuInfo;
import android.widget.AdapterView.OnItemClickListener;
import android.widget.AdapterView.OnItemLongClickListener;
import android.widget.BaseAdapter;
import android.widget.EditText;
import android.widget.ImageView;
import android.widget.ListView;
import android.widget.PopupMenu;
import android.widget.TextView;
import android.widget.Toast;

/**
 * This class creates the Wallet_list activity which serves as the main activity after the user pairs a wallet.
 * It loads the wallet metadata from shared preferences and creates a listview of paired wallets.
 * In the background it opens a connection to the wallet and waits for transactions. When a transaction is received
 * it displays a dialog box to the user asking for authorization. If it's authorized, it calculates the signature
 * and sends it back to the wallet.
 */
public class Wallet_list extends Activity {
	ListView lv1;
	public static JSONObject req;
	private CustomListAdapter listAdapter;
	public GlobalEvents singletonEvents;

	public void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_wallet_list);
        
        // swipe listener
        final SwipeRefreshLayout swipeLayout = (SwipeRefreshLayout) findViewById(R.id.wallet_list_swipe_container);
        swipeLayout.setColorScheme(R.color.Pull_Refresh_Color,R.color.Pull_Refresh_Color,R.color.Pull_Refresh_Color,R.color.Pull_Refresh_Color);
        swipeLayout.setOnRefreshListener(new OnRefreshListener(){
			@Override
			public void onRefresh() {
				updateListViewData();
				swipeLayout.setRefreshing(false);
			}
        });
        
        
        //Create the list view
        try { setListView(true); } catch (InterruptedException e) { e.printStackTrace(); } catch (JSONException e) { e.printStackTrace(); }
        //Start the AsyncTask which waits for new transactions
        connectToWallets(); 
  		
  		/**
  		 * Events
  		 */
  		this.singletonEvents = GlobalEvents.SharedGlobal();
  		singletonEvents.onSetPendingGCMRequestToSeen.AddListener(this, "onSetPendingGCMRequestToSeen");
    }

	/**
	 * Creates the listview component and defines behavior to a long press on a list item.
	 * 
	 * @param launchNewGCMListener
	 * @throws InterruptedException
	 * @throws JSONException
	 */
	void setListView(boolean launchNewGCMListener) throws InterruptedException, JSONException{
        ArrayList walletList = getListData();
        lv1 = (ListView) findViewById(R.id.custom_list);
        lv1.setLongClickable(true);
        listAdapter = new CustomListAdapter(this, walletList);
        lv1.setAdapter(listAdapter);
        //registerForContextMenu(lv1);
        lv1.setOnItemClickListener(new OnItemClickListener()
        {
           @Override
           public void onItemClick(AdapterView<?> adapter, View v, int position,
                 long arg3) 
           {
        	   final int index = position;
        	   int cnt = ((WalletItem)listAdapter.getItem(index)).getPendingGCMRequests().size();
        	   boolean showPending = cnt>0? true:false;
        	   if (showPending){
        		   Intent i = new Intent(Wallet_list.this, ActivityPendingRequests.class);
        		   WalletItem wi = (WalletItem)lv1.getItemAtPosition(index);
        		   i.putExtra("walletID", wi.getWalletIDString());
        		   i.putExtra("walletName", wi.getWalletLabel());
        		   startActivity (i);
        	   }
           }
        });
        lv1.setOnItemLongClickListener(new OnItemLongClickListener()
        {
           @Override
           public boolean onItemLongClick(AdapterView<?> adapter, View v, int position,
                 long arg3) 
           {
        	   final int index = position;
        	   ArrayList<BAPopupMenu.PopupButton> buttons = new ArrayList<BAPopupMenu.PopupButton>();
        	   buttons.add(new BAPopupMenu.PopupButton("Rename",true));
        	   buttons.add(new BAPopupMenu.PopupButton("Details",true));
        	   buttons.add(new BAPopupMenu.PopupButton("Delete",true));
        	   
                new BAPopupMenu(getApplicationContext(),v)
                .setButtons(buttons)
                .setActionsListener(new BAPopupMenu.ActionsListener(){
					@Override
					public void pressed(MenuItem item) {
						onPopupMenuItemSelected(item.getTitle().toString(),index);
					}
                }).show();
				return true;
           }
        });
	}
	
	public void updateListViewData(){
		try {
			listAdapter.updateData(addGCMPendingRequestsToWallets(getListData()));
		} catch (InterruptedException e) { e.printStackTrace(); } catch (JSONException e) { e.printStackTrace(); }
	}

	/**Inflates the menu and adds it to the action bar*/
	@Override
	public boolean onCreateOptionsMenu(Menu menu) {
		getMenuInflater().inflate(R.menu.main, menu);
		return true;
	}

	/**This method handles the clicks in the option menu*/
	@Override
	public boolean onOptionsItemSelected(MenuItem item) {
		int id = item.getItemId();
		if (id == R.id.action_pair_wallet){
			startActivity (new Intent(Wallet_list.this, Pair_wallet.class));;
		}
		if (id == R.id.action_how_it_works){
			startActivity (new Intent(Wallet_list.this, How_it_works.class));;
		}
		if (id == R.id.action_show_seed){
			startActivity (new Intent(Wallet_list.this, Show_seed.class));
		}
		if (id == R.id.action_settings){
			startActivity (new Intent(Wallet_list.this, Settings.class));
		}
		return super.onOptionsItemSelected(item);
	}
    
    /**Handles the clicks in the context menu*/
	public void onPopupMenuItemSelected(String title, final int index){
        //Re-pairs with the wallet
        if(title == "Show Pending Requests"){
        	Intent i = new Intent(Wallet_list.this, ActivityPendingRequests.class);
        	WalletItem wi = (WalletItem)lv1.getItemAtPosition(index);
        	i.putExtra("walletID", wi.getWalletIDString());
        	i.putExtra("walletName", wi.getWalletLabel());
        	startActivity (i);
        }
       	//Displays a dialog allowing the user to rename the wallet in the listview
    	else if(title=="Rename"){
    		BASingleInputDialog alert = new BASingleInputDialog(this);
    		alert.setTitle("Rename");
    		alert.setSecondaryTitle("Enter a name for this wallet:");
    		alert.requestWindowFeature(Window.FEATURE_NO_TITLE);
    		alert.setOkButtonListener(new SingleInputOnClickListener(){
				@Override
				public void onClick(BAAlertDialogBase alert, String input) {
					if(input.length() > 2){
						Object o = lv1.getItemAtPosition(index);
		    			WalletItem Data = (WalletItem) o;		    			
		    			String wdata = Data.getWalletIDString();
		    			BAPreferences.WalletPreference().setName(wdata, input);
		    			updateListViewData();
					}
					else {
						runOnUiThread(new Runnable() {
            				public void run() {
            					Toast.makeText(getApplicationContext(), "Name must be at least 2 characters", Toast.LENGTH_LONG).show();
            				}
            			});
					}
				}
    		});
    		alert.setCancelButtonListener(new SingleInputOnClickListener(){
    			@Override
				public void onClick(BAAlertDialogBase alert, String input) {
    				
    			}
    		});
    		
    		alert.show();    
    	}
    	else if(title=="Details"){
    		Intent i = new Intent(Wallet_list.this, PairingDetails.class);
        	WalletItem wi = (WalletItem)lv1.getItemAtPosition(index);
        	i.putExtra("walletName", wi.getWalletLabel());
        	i.putExtra("accountID", wi.getWalletIDString());
        	i.putExtra("externalIP", BAPreferences.WalletPreference().getExternalIP(wi.getWalletIDString(), ""));
        	i.putExtra("internalIP", BAPreferences.WalletPreference().getLocalIP(wi.getWalletIDString(), ""));
        	i.putExtra("icon", wi.getIcon());
        	startActivity (i);
    	}
       	//Displays a dialog prompting the user to confirm they want to delete a wallet from the listview
    	else if(title=="Delete"){
    		BADeleteDialog alert = new BADeleteDialog(this);
    		alert.setTitle("Delete");
    		alert.setSecondaryTitle("Are you sure you want to delete this wallet?");
    		alert.setDeleteText("Do not continue if this wallet has a positive balance as you will not be able to sign any more transactions.");
    		alert.setDialogCenterIcon(R.drawable.ic_alert_icon);
    		alert.setDeleteButtonListener(new DeleteOnClickListener(){
				@Override
				public void onClick(BAAlertDialogBase alert) {
					Object o = lv1.getItemAtPosition(index);
        			WalletItem Data = (WalletItem) o;
        			String wdata = Data.getWalletIDString();
        			BAPreferences.WalletPreference().setDeleted(wdata, true);
        	        try { setListView(false); } catch (InterruptedException e) { e.printStackTrace(); } catch (JSONException e) { e.printStackTrace(); }
				}
    		});
    		alert.setCancelButtonListener(new DeleteOnClickListener(){
				@Override
				public void onClick(BAAlertDialogBase alert) { }
    		});
    		alert.show();
    	}
    	
	}

	/**
	 * Disable the back button because the device is now paired and any activity the user needs can be 
	 * accessed from the menu. 
	 */
	public boolean onKeyDown(int keyCode, KeyEvent event) {

	    if(keyCode == KeyEvent.KEYCODE_BACK)
	    {
	            moveTaskToBack(true);
	            return true;
	    }
		return false;
	}

	@SuppressWarnings("unchecked")
	public void removePendingRequestFromListAndThenUpdate(String requestID) throws JSONException{
		BAPreferences.ConfigPreference().removePendingRequestFromListAndThenUpdate(requestID);
	
		// update adapter
		try {
			listAdapter.updateData(addGCMPendingRequestsToWallets(listAdapter.listData));
		} catch (InterruptedException e) { e.printStackTrace(); } catch (JSONException e) { e.printStackTrace(); }
	}
	
	@SuppressWarnings("unchecked")
	private ArrayList addGCMPendingRequestsToWallets(ArrayList wallets) throws InterruptedException, JSONException{
		
		ArrayList<String> pending = BAPreferences.ConfigPreference().getPendingList();
		for(WalletItem walletData:(ArrayList<WalletItem>)wallets){
			walletData.pendingGCMRequests = new ArrayList<JSONObject>();
			// Load pending request
			for(String req:pending){
				JSONObject o = BAPreferences.ConfigPreference().getPendingRequestAsJsonObject(req);
				String pendingReqWalletID = Long.toString(PairingProtocol.getWalletIndexFromString(o.getString("WalletID")));
				String walletID = walletData.getWalletIDString();
				if(pendingReqWalletID.equals(walletID))
				if(o.getBoolean("seen") == false)
					walletData.addPendingGCMRequest(o);
			}
		}
		return wallets;		
	}

	/**This method loads the metadata from Shared Preferences needed to display in the listview
	 * @throws InterruptedException 
	 * @throws JSONException */
    @SuppressWarnings("unchecked")
	private ArrayList getListData() throws InterruptedException, JSONException {
	    Set<Long> walletIndexSet= BAPreferences.ConfigPreference().getWalletIndexList();
	    boolean isTestnet = BAPreferences.ConfigPreference().getTestnet(false);
    	ArrayList results = new ArrayList();
    	
    	//Load the data for each wallet and add it to a WalletItem object
    	for (Long i:walletIndexSet) {
    		String wdata = Long.toString(i);
    		WalletItem walletData = new WalletItem();
    		Boolean deleted = BAPreferences.WalletPreference().getDeleted(wdata, false);
    		int networkType = BAPreferences.WalletPreference().getNetworkType(wdata, 1);// default main net
    		if(deleted) continue;
    		if((isTestnet && networkType == 1) || (isTestnet == false && networkType == 0)) // get only current network type wallets
    			continue;
    		
    		walletData.setWalletID(i);
    		walletData.setWalletLabel(BAPreferences.WalletPreference().getName(wdata, "Null"));
    		//Decide which icon to display
    		String typ = BAPreferences.WalletPreference().getType(wdata, "Null");
    		if (	 typ.equals("blockchain"	))	{walletData.setIcon(R.drawable.ic_blockchain_logo);}
    		else if (typ.equals("electrum"		))	{walletData.setIcon(R.drawable.ic_electrum_logo);} 
    		else if (typ.equals("hive"			))	{walletData.setIcon(R.drawable.ic_hive_logo);}
    		else if (typ.equals("multibit"		))	{walletData.setIcon(R.drawable.multibit_logo);}
    		else if (typ.equals("bitcoincore"	))	{walletData.setIcon(R.drawable.ic_bitcoin_logo);}
    		else if (typ.equals("armory"		))	{walletData.setIcon(R.drawable.armory_logo);}
        	else if (typ.equals("darkwallet"	))	{walletData.setIcon(R.drawable.darkwallet_logo);}
        	else 									{walletData.setIcon(R.drawable.ic_authenticator_logo);}
    		results.add(walletData);
    	}
        return addGCMPendingRequestsToWallets(results);
    }
    
    /**Creates an object that holds the metadata for each wallet to include in the listview*/
    public class WalletItem {
    	 
        private String walletLabel;
        private ArrayList<JSONObject> pendingGCMRequests;
        private int icon;
        private long WalletID;
        
        public long getWalletID() {
        	return WalletID;
        }
        
        public String getWalletIDString() {
        	return Long.toString(WalletID);
        }
        
        public void setWalletID(long num) {
        	this.WalletID = num;
        }
     
        public String getWalletLabel() {
            return walletLabel;
        }
     
        public void setWalletLabel(String label) {
            this.walletLabel = label;
        }
        
        // pending gcm requests
        public ArrayList<JSONObject> getPendingGCMRequests() {
        	if(this.pendingGCMRequests == null)
        		this.pendingGCMRequests = new ArrayList<JSONObject>();
            return this.pendingGCMRequests;
        }
     
        public void addPendingGCMRequest(JSONObject req) {
        	getPendingGCMRequests().add(req);
        }
     
        public int getIcon() {
            return icon;
        }
     
        public void setIcon(int blockchainInfoLogo) {
            this.icon = blockchainInfoLogo;
        }
     
        @Override
        public String toString() {
            return "[ Wallet Label=" + walletLabel + ", WalletID=" + 
            		WalletID + " , Icon=" + icon + "]";
        }
    }

    /**Creates a custom adapter object for the listview*/
    public class CustomListAdapter extends BaseAdapter {
    	
    	private ArrayList listData;
    	private LayoutInflater layoutInflater;
 
    	public CustomListAdapter(Context context, ArrayList listData) {
    		this.listData = listData;
    		layoutInflater = LayoutInflater.from(context);
    	}
    	
    	public void updateData(ArrayList data){
    		this.listData = data;
    		this.notifyDataSetChanged();
    	}
 
    	@Override
    	public int getCount() {
    		return listData.size();
    	}
 
    	@Override
    	public Object getItem(int position) {
    		return listData.get(position);
    	}
    	
    	@Override
    	public long getItemId(int position) {
    		return position;
    	}
 
    	public View getView(int position, View convertView, ViewGroup parent) {
    		ViewHolder holder;
    		if (convertView == null) {
    			convertView = layoutInflater.inflate(R.layout.list_item, null);
    			holder = new ViewHolder();
    			holder.walletIcon = (ImageView) convertView.findViewById(R.id.wallet_icon);
    			holder.walletPendingRequestCntView = (TextView) convertView.findViewById(R.id.wallet_new_requests);
    			holder.walletLabelView = (TextView) convertView.findViewById(R.id.wallet_label);
    			holder.walletIDView = (TextView) convertView.findViewById(R.id.wallet_id);
    			
    			convertView.setTag(holder);
    		} else {
    			holder = (ViewHolder) convertView.getTag();
    		}
    		holder.walletIcon.setImageResource(((WalletItem) listData.get(position)).getIcon());
    		int cnt = ((WalletItem) listData.get(position)).getPendingGCMRequests().size();
    		if (cnt > 0) {holder.walletPendingRequestCntView.setText("  " + Integer.toString(cnt) + "  ");}
    		else {holder.walletPendingRequestCntView.setText("");}
    		holder.walletLabelView.setText(((WalletItem) listData.get(position)).getWalletLabel());
    		holder.walletIDView.setText(((WalletItem) listData.get(position)).getWalletIDString());
    		
    		
 
    		return convertView;
    	}
 
    	class ViewHolder {
    		ImageView walletIcon;
    		TextView walletLabelView;
    		TextView walletIDView;
    		TextView walletPendingRequestCntView;
    		
    	}
    }
    
    /**
     * This is a class that runs in the background and connects to the wallet and waits to receive a transaction.
     * When one is received, it loads the dialog box.
     */
    public void connectToWallets() {
    	new Thread() {
    		@Override
    		public void run() {
    			TxData tx = null;
    	    	ProcessGCMRequest.ProcessReturnObject ret = null;
    	    	Socket persistentSocketForTheProcess = null;
    			
    	    	/**
    	    	 * 
    	    	 */
        		//Load the GCM settings from shared preferences
                //SharedPreferences settings = getSharedPreferences("ConfigFile", 0);
                Boolean GCM = BAPreferences.ConfigPreference().getGCM(true);//settings.getBoolean("GCM", true);
                if(GCM){
                	// Handle a request that was pressed by the user
                	String reqString = null;
                	if(getIntent().getStringExtra("RequestID") != null){
                		//SharedPreferences settings2 = getSharedPreferences("ConfigFile", 0);
                		reqString = BAPreferences.ConfigPreference().getPendingRequestAsString(getIntent().getStringExtra("RequestID"));//settings2.getString(getIntent().getStringExtra("RequestID"), null);
                		ProcessGCMRequest processor = new ProcessGCMRequest(getApplicationContext());
                		ret = processor.ProcessRequest(reqString);
                		
                		// Connect
                		BAPreferences.ConfigPreference().setRequest(false);
                    	
                		//Open a new connection
                    	String[] ips = new String[] { ret.IPAddress, ret.LocalIP};
                    	
                    	SecretKey sharedsecret = Utils.getAESSecret(getApplicationContext(), ret.walletnum); 
                    	
                    	//Create a new message object for receiving the transaction.
                    	Message msg = null;
                    	
        				//send request id
                    	persistentSocketForTheProcess = null;
        				try {
        					msg = new Message(ips);
    						persistentSocketForTheProcess = msg.sentRequestID(
    								getIntent().getStringExtra("RequestID"),
    								getIntent().getStringExtra("WalletID"));
    					} catch (CouldNotSendRequestIDException e1) {
    						e1.printStackTrace();
    					}
        				if(persistentSocketForTheProcess != null)
    	        			try {tx = msg.receiveTX(sharedsecret, persistentSocketForTheProcess);} 
    	        			catch (Exception e) {e.printStackTrace();}
                	}            		
                }
                else { // TODO !
                	
                }
                
                /**
                 * 
                 */
                if(tx != null)
                	launchDialog(persistentSocketForTheProcess, tx, ret.walletnum);                
    		}
    		
    		private void launchDialog(final Socket s, final TxData tx, final long walletNum) {
    			runOnUiThread(new Runnable() {
	    			public void run() {
	    				try {
	    					new ConfirmTxDialog(s, 
	    		     				   tx, 
	    		     				   Wallet_list.this, 
	    		     				   walletNum, 
	    		     				   new TxDialogResponse(){
	    													@Override
	    													public void onAuthorizedTx() {
	    														proccessReq();
	    													}
	    								
	    													@Override
	    													public void onNotAuthorizedTx() {
	    														proccessReq();
	    													}
	    								
	    													@Override
	    													public void onCancel() {
	    														// TODO Auto-generated method stub
	    														
	    													}
	    													
	    													public void proccessReq(){
	    														try {
	    															JSONObject jo = BAPreferences.ConfigPreference().getPendingRequestAsJsonObject(getIntent().getStringExtra("RequestID"));
	    															jo.put("seen", true);
	    															BAPreferences.ConfigPreference().setPendingRequest(jo.getString("RequestID"), jo);
	    											    		   // remove from pending requests
	    											    		   removePendingRequestFromListAndThenUpdate(getIntent().getStringExtra("RequestID"));
	    														} catch (JSONException e) { e.printStackTrace(); }
	    													}

	    													@Override
	    													public void OnError() {
	    														runOnUiThread(new Runnable() {
	    								            				public void run() {
	    								            					Toast.makeText(getApplicationContext(), "Failed !!", Toast.LENGTH_LONG).show();
	    								            				}
	    								            			});
	    													}
	    								        			   
	    								        		   });
	    				}
	    				catch(Exception e) { }
					}
				});    			
    		}
    	}.start();
    }
            
    /**
     * Events
     */
    public void onSetPendingGCMRequestToSeen(Object Sender, Object Arguments)
	{
    	updateListViewData();
	}
}