package mobile.app_for_test;

public class Config {
	public static final int    PROTOCOL_TCP				= 6;
    public static final int    PROTOCOL_UDP				= 17;
    public static final int    PROTOCOL_OFFSET			= 9;
    
    public static final int    BUYER_CLIENT_PORT		= 9030;
	
	public static final int    DEFAULT_PORT_NUMBER      = 9040;
    public static final int    DEFAULT_MTU              = 1400;
    public static final int    MAX_MTU                  = 1500;
    public static final int    DEFAULT_POLL_MS          = 10;
    public static final String DEFAULT_VPN_CLIENT_ADDR  = "10.13.10.2";
    public static final String DEFAULT_VPN_GATEWAY_ADDR = "10.13.10.1"; // Do we need this?
    public static final int	   DEFAULT_LOCAL_PORT		= 9030;
    public static final int    DEFAULT_UDP_TIMEOUT		= 5000;
    public static final int    TCP_STATE_INITIAL		= 8752; //just a random number
    public static final int    TCP_STATE_SYN			= 8753;
    //public static final int    TCP_STATE_SYNACK_SENT	= 8754;
    public static final int    TCP_STATE_ACTIVE			= 8755;
    public static final int    TCP_STATE_FIN			= 8756;
    public static final int    PKT_TYPE_SYNACK			= 3429;
    public static final int    PKT_TYPE_DATAACK			= 3430;
    public static final int    PKT_TYPE_FINACK			= 3431;
    public static final int    PKT_TYPE_DATA			= 3432;
    public static final int	   TCP_LOST_TIMEOUT			= 13; // in milliseconds
}
