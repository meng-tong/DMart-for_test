package mobile.app_for_test;

import java.util.regex.Matcher;
import java.util.regex.Pattern;

public final class HelperFunc {
	private static final String IP_PATTERN =
								"^([01]?\\d\\d?|2[0-4]\\d|25[0-5])\\." +
								"([01]?\\d\\d?|2[0-4]\\d|25[0-5])\\." +
								"([01]?\\d\\d?|2[0-4]\\d|25[0-5])\\." +
								"([01]?\\d\\d?|2[0-4]\\d|25[0-5])$";
	
	public static boolean isIP(String str) {
		Pattern pattern = Pattern.compile(IP_PATTERN);
		Matcher matcher = pattern.matcher(str);
      
		boolean ipFlag = matcher.find();

		return ipFlag;
    }
	
	public static long calcChecksum(byte[] content) {
		int length = content.length;
		int i = 0;
		
		long checksum=0, data;
		// Handle all pairs
		while (length > 1) {
			// Corrected to include @Andy's edits and various comments on Stack Overflow
	        data = (((content[i] << 8) & 0xFF00) | ((content[i + 1]) & 0xFF));
	        checksum += data;
	        // 1's complement carry bit correction in 16-bits (detecting sign extension)
	        if ((checksum & 0xFFFF0000) > 0) {
	        	checksum = checksum & 0xFFFF;
	        	checksum += 1;
	        }
	        
	        i += 2;
	        length -= 2;
        }
		
		// Handle remaining byte in odd length buffers
		if (length > 0) {
			// Corrected to include @Andy's edits and various comments on Stack Overflow
			checksum += (content[i] << 8 & 0xFF00);
			// 1's complement carry bit correction in 16-bits (detecting sign extension)
			if ((checksum & 0xFFFF0000) > 0) {
				checksum = checksum & 0xFFFF;
	        	checksum += 1;
	        }
		}
		
		// Final 1's complement value correction to 16-bits
		checksum = ~checksum;
		checksum = checksum & 0xFFFF;
		return checksum;
	}
}
