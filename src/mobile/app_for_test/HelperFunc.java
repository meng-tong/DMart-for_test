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
}
