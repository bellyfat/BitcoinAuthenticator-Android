package wallet;

import java.io.*;
import java.net.*;
import java.util.Arrays;
import java.util.LinkedHashMap;
import java.util.Map;

import javax.crypto.*;

import org.json.simple.JSONObject;
import org.json.simple.JSONValue;

/**
 * This is the wallet side of the Pairing Protocol. It uses UpNp to map a port on the router if there is one,
 * opens a port for Alice, displays a QR code for the user to scan, and receives the master public key and chaincode.
 */
public class PairingProtocol {
	public static DataInputStream in;
	public static DataOutputStream out;
	public static byte[] chaincode;
	public static byte[] mPubKey;
	public static byte[] gcmRegId;
	public static byte[] pairingID;
	public static SecretKey sharedsecret;
	
  public static void run (String args) throws Exception {

	  String walletType = args;
	  final int port = 1234;

	  // Open a port and wait for a connection
	  UpNp plugnplay = new UpNp();
	  plugnplay.run(null);
	  ServerSocket ss = new ServerSocket (port);
	  System.out.println("Listening for Alice on port "+port+"...");
	  String ip = plugnplay.getExternalIP();
	  String localip = plugnplay.getLocalIP().substring(1);
	  
	  //Generate 256 bit key.
	  KeyGenerator kgen = KeyGenerator.getInstance("AES");
      kgen.init(256);

      // Generate the secret key specs.
      sharedsecret = kgen.generateKey();
      byte[] raw = sharedsecret.getEncoded();
      String key = bytesToHex(raw);
	  
	  //Display a QR code for the user to scan
	  QRCode PairingQR = new QRCode(ip, localip, walletType, key);
	  DisplayQR QR = new DisplayQR();
	  QR.main(null);    
	  Socket socket = ss.accept();
	  QR.CloseWindow();
	  System.out.println("Connected to Alice");
    
	  // Receive Master Public Key and Chaincode
	  in = new DataInputStream(socket.getInputStream());
	  out = new DataOutputStream(socket.getOutputStream());
	  int keysize = in.readInt();
	  byte[] cipherKeyBytes = new byte[keysize];
	  in.read(cipherKeyBytes);
	  Cipher cipher = Cipher.getInstance("AES/ECB/PKCS5PADDING");
	  cipher.init(Cipher.DECRYPT_MODE, sharedsecret);
	  String payload = bytesToHex(cipher.doFinal(cipherKeyBytes));
    
	  // Verify HMAC
	  byte[] testpayload = hexStringToByteArray(payload.substring(0,payload.length()-64));
	  byte[] hash = hexStringToByteArray(payload.substring(payload.length()-64,payload.length()));
	  Mac mac = Mac.getInstance("HmacSHA256");
	  mac.init(sharedsecret);
	  byte[] macbytes = mac.doFinal(testpayload);
	  if (Arrays.equals(macbytes, hash)){
		  mPubKey = hexStringToByteArray(payload.substring(0,66));
		  chaincode = hexStringToByteArray(payload.substring(66,130));
		  pairingID = hexStringToByteArray(payload.substring(130,138));
		  gcmRegId = hexStringToByteArray(payload.substring(138,payload.length()-64));
		  System.out.println("Received Master Public Key: " + bytesToHex(mPubKey) + "\n" +
				  			 "chaincode: " +  bytesToHex(chaincode) + "\n" +
				  			 "gcmRegId: " +  new String(gcmRegId) + "\n" + 
				  			 "pairing ID: " + new String(pairingID));
		  //Save mPubKey and the Chaincode to file
		  WalletFile file = new WalletFile();
		  file.writePairingData(bytesToHex(mPubKey), bytesToHex(chaincode), key, new String(gcmRegId));
	  }
	  else {
		  System.out.println("Message authentication code is invalid");
	  }
	  
	  //dispose
	  //in.close();
	  //out.close();
	  //plugnplay.removeMapping();
	  //ss.close();
	  // Return to main
	  Main.inputCommand();

  }
  
  final protected static char[] hexArray = "0123456789ABCDEF".toCharArray();
	public static String bytesToHex(byte[] bytes) {
	    char[] hexChars = new char[bytes.length * 2];
	    for ( int j = 0; j < bytes.length; j++ ) {
	        int v = bytes[j] & 0xFF;
	        hexChars[j * 2] = hexArray[v >>> 4];
	        hexChars[j * 2 + 1] = hexArray[v & 0x0F];
	    }
	    return new String(hexChars);
	}
	
	public static byte[] hexStringToByteArray(String s) {
	    int len = s.length();
	    byte[] data = new byte[len / 2];
	    for (int i = 0; i < len; i += 2) {
	        data[i / 2] = (byte) ((Character.digit(s.charAt(i), 16) << 4)
	                             + Character.digit(s.charAt(i+1), 16));
	    }
	    return data;
	}
}