package com.openrsc.server.login;

import com.openrsc.server.Server;
import com.openrsc.server.database.GameDatabaseException;
import com.openrsc.server.database.struct.PlayerLoginData;
import com.openrsc.server.net.PacketBuilder;
import com.openrsc.server.util.rsc.DataConversions;
import com.openrsc.server.util.rsc.LoginResponse;
import com.openrsc.server.util.rsc.RegisterLoginResponse;
import io.netty.channel.Channel;
import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;

import java.net.InetSocketAddress;

/**
 * Used to create a Character on the Login thread
 */
public class CharacterCreateRequest extends LoginExecutorProcess{

	/**
	 * The asynchronous logger.
	 */
	private static final Logger LOGGER = LogManager.getLogger();

	private final Server server;
	private String ipAddress;
	private String username;
	private String password;
	private String email;
	private int clientVersion;
	private boolean authenticClient;
	private Channel channel;
	private boolean isSimRegister;

	public CharacterCreateRequest(final Server server, final Channel channel, final String username, final String password, final boolean isAuthenticClient, final int clientVersion) {
		this.server = server;
		this.setEmail("");
		this.setUsername(DataConversions.sanitizeUsername(username));
		this.setPassword(password);
		this.setAuthenticClient(isAuthenticClient);
		this.setChannel(channel);
		this.setIpAddress(((InetSocketAddress) getChannel().remoteAddress()).getAddress().getHostAddress());
		this.setClientVersion(clientVersion);
		this.isSimRegister = false;
	}

	public CharacterCreateRequest(final Server server, final Channel channel, final String username, final String password, final String email, final boolean isAuthenticClient, final int clientVersion) {
		this.server = server;
		this.setEmail(email);
		this.setUsername(DataConversions.sanitizeUsername(username));
		this.setPassword(password);
		this.setAuthenticClient(isAuthenticClient);
		this.setChannel(channel);
		this.setIpAddress(((InetSocketAddress) getChannel().remoteAddress()).getAddress().getHostAddress());
		this.setClientVersion(clientVersion);
		this.isSimRegister = false;
	}

	public CharacterCreateRequest(final Server server, final String username, final String ip, final int clientVersion) {
		this.server = server;
		this.setEmail("");
		this.setUsername(DataConversions.sanitizeUsername(username));
		this.setAuthenticClient(clientVersion <= 235);
		this.setChannel(null);
		this.setIpAddress(ip);
		this.setClientVersion(clientVersion);
		this.isSimRegister = true;
	}

	public String getIpAddress() {
		return ipAddress;
	}

	private void setIpAddress(final String ipAddress) {
		this.ipAddress = ipAddress;
	}

	public String getUsername() {
		return username;
	}

	private void setUsername(final String username) {
		this.username = username;
	}

	public String getPassword() {
		return password;
	}

	private void setPassword(final String password) {
		this.password = password;
	}

	public boolean getAuthenticClient() {
		return authenticClient;
	}

	private void setAuthenticClient(final boolean authenticClient) {
		this.authenticClient = authenticClient;
	}

	public Channel getChannel() {
		return channel;
	}

	private void setChannel(final Channel channel) {
		this.channel = channel;
	}

	public String getEmail() {
		return email;
	}

	private void setEmail(final String email) {
		this.email = email;
	}

	public Server getServer() {
		return server;
	}

	public int getClientVersion() {
		return clientVersion;
	}

	private void setClientVersion(final int clientVersion) {
		this.clientVersion = clientVersion;
	}

	protected void processInternal() {
		if (!getServer().getConfig().WANT_PACKET_REGISTER) {
			getChannel().writeAndFlush(new PacketBuilder().writeByte((byte) RegisterLoginResponse.UNSUCCESSFUL).toPacket());
			getChannel().close();
			return;
		}
		
		if (getAuthenticClient()) {
			int registerResponse = validateRegister();
			if (clientVersion <= 204) {
				registerResponse = RegisterLoginResponse.translateNewToOld(registerResponse, clientVersion, true);
			}
			getChannel().writeAndFlush(new PacketBuilder().writeByte((byte) registerResponse).toPacket());
			if (registerResponse != RegisterLoginResponse.REGISTER_SUCCESSFUL) {
				getChannel().close();
			}
			LOGGER.info("Processed register request for " + getUsername() + " response: " + registerResponse);
		} else {
			try {
				boolean applyHarshRegistration = false; // could potentially fall in config?

				if (getUsername().length() < 2 || getUsername().length() > 12) {
					getChannel().writeAndFlush(new PacketBuilder().writeByte((byte) 7).toPacket());
					getChannel().close();
					return;
				}

				if (isDisallowedUsername(getUsername())) {
					getChannel().writeAndFlush(new PacketBuilder().writeByte((byte) 8).toPacket());
					getChannel().close();
					return;
				}

				if (getPassword().length() < 4 || getPassword().length() > 20) {
					getChannel().writeAndFlush(new PacketBuilder().writeByte((byte) 8).toPacket());
					getChannel().close();
					return;
				}

				if (getServer().getConfig().WANT_EMAIL) {
					if (!DataConversions.isValidEmailAddress(email)) {
						getChannel().writeAndFlush(new PacketBuilder().writeByte((byte) 6).toPacket());
						getChannel().close();
						return;
					}
				}

				if (getServer().getConfig().WANT_REGISTRATION_LIMIT) {
					int registerTimeout = applyHarshRegistration ? 1440 : 1; //time in minutes
					boolean recentlyRegistered = (getServer().getConfig().IS_LOCALHOST_RESTRICTED && getIpAddress().equals("127.0.0.1"))
					|| (!getIpAddress().equals("127.0.0.1") && getServer().getDatabase().checkRecentlyRegistered(getIpAddress(), registerTimeout));
					if (recentlyRegistered) {
						LOGGER.info(getIpAddress() + " - Registration failed: Registered recently.");
						getChannel().writeAndFlush(new PacketBuilder().writeByte((byte) 5).toPacket());
						getChannel().close();
						return;
					}
				}

				boolean usernameExists = getServer().getDatabase().playerExists(getUsername());
				if (usernameExists) {
					LOGGER.info(getIpAddress() + " - Registration failed: Forum Username already in use.");
					getChannel().writeAndFlush(new PacketBuilder().writeByte((byte) 2).toPacket());
					getChannel().close();
					return;
				}

				/* Create the game character */
				final int playerId = getServer().getDatabase().createPlayer(getUsername(), getEmail(),
					DataConversions.hashPassword(getPassword(), null),
					System.currentTimeMillis() / 1000, getIpAddress());

				if (playerId == -1) {
					LOGGER.info(getIpAddress() + " - Registration failed: Player id not found.");
					getChannel().writeAndFlush(new PacketBuilder().writeByte((byte) 6).toPacket());
					getChannel().close();
					return;
				}

				LOGGER.info(getIpAddress() + " - Registration successful");
				getChannel().writeAndFlush(new PacketBuilder().writeByte((byte) 0).toPacket());
			} catch (Exception e) {
				LOGGER.catching(e);
				getChannel().writeAndFlush(new PacketBuilder().writeByte((byte) 5).toPacket());
				getChannel().close();
			}
		}
	}

	public byte validateRegister() {
		PlayerLoginData playerData;
		try {
			boolean applyHarshRegistration = false; // could potentially fall in config?

			playerData = getServer().getDatabase().getPlayerLoginData(username);

			boolean isAdmin = getServer().getPacketFilter().isHostAdmin(getIpAddress());

			if (!getIpAddress().equals("127.0.0.1") && getServer().getPacketFilter().getPasswordAttemptsCount(getIpAddress()) >= getServer().getConfig().MAX_PASSWORD_GUESSES_PER_FIVE_MINUTES && !isAdmin) {
				return (byte) RegisterLoginResponse.LOGIN_ATTEMPTS_EXCEEDED;
			}

			if (getServer().getPacketFilter().isHostIpBanned(getIpAddress()) && !isAdmin) {
				return (byte) RegisterLoginResponse.ACCOUNT_TEMP_DISABLED;
			}

			if (getClientVersion() != getServer().getConfig().CLIENT_VERSION && !isAdmin) {
				if (getClientVersion() > 10000) {
					if (getServer().getConfig().ENFORCE_CUSTOM_CLIENT_VERSION) {
						return (byte) RegisterLoginResponse.CLIENT_UPDATED;
					}
				} else {
					if (getServer().getConfig().WANT_CUSTOM_SPRITES) {
						return (byte) RegisterLoginResponse.CLIENT_UPDATED;
					}
				}
			}

			if (getServer().getWorld().getPlayers().size() >= getServer().getConfig().MAX_PLAYERS && !isAdmin) {
				return (byte) RegisterLoginResponse.WORLD_IS_FULL;
			}

			if (getServer().getDatabase().playerExists(getUsername())) {
				return (byte) RegisterLoginResponse.USERNAME_TAKEN_OR_INVALID;
			}

			if (getServer().getWorld().getPlayer(DataConversions.usernameToHash(getUsername())) != null) {
				return (byte) RegisterLoginResponse.ACCOUNT_LOGGEDIN;
			}

			if (((getServer().getConfig().IS_LOCALHOST_RESTRICTED && getIpAddress().equals("127.0.0.1"))
				|| (!getIpAddress().equals("127.0.0.1") && getServer().getPacketFilter().getPlayersCount(getIpAddress()) >= getServer().getConfig().MAX_PLAYERS_PER_IP) && !isAdmin)) {
				return (byte) RegisterLoginResponse.IP_IN_USE;
			}

			final long banExpires = playerData != null ? playerData.banned : 0;
			if (banExpires == -1 && !isAdmin) {
				return (byte) RegisterLoginResponse.ACCOUNT_PERM_DISABLED;
			}

			final double timeBanLeft = (double) (banExpires - System.currentTimeMillis());
			if (timeBanLeft >= 1 && !isAdmin) {
				return (byte) RegisterLoginResponse.ACCOUNT_TEMP_DISABLED;
			}

			if (isDisallowedUsername(getUsername())) {
				return (byte) RegisterLoginResponse.USERNAME_TAKEN_DISALLOWED;
			}

			if (getServer().getConfig().WANT_REGISTRATION_LIMIT) {
				int registerTimeout = applyHarshRegistration ? 1440 : 1; //time in minutes
				boolean recentlyRegistered = (getServer().getConfig().IS_LOCALHOST_RESTRICTED && getIpAddress().equals("127.0.0.1"))
					|| (!getIpAddress().equals("127.0.0.1") && getServer().getDatabase().checkRecentlyRegistered(getIpAddress(), registerTimeout));
				if (recentlyRegistered) {
					LOGGER.info(getIpAddress() + " - Registration failed: Registered recently.");
					return (byte) RegisterLoginResponse.LOGIN_ATTEMPTS_EXCEEDED; // closest match for authentic client
				}
			}

			/* Create the game character */
			int playerId;
			if (!isSimRegister) {
				playerId = getServer().getDatabase().createPlayer(getUsername(), getEmail(),
					DataConversions.hashPassword(getPassword(), null),
					System.currentTimeMillis() / 1000, getIpAddress());
			} else {
				playerId = 1;
			}

			if (playerId == -1) {
				LOGGER.info(getIpAddress() + " - Registration failed: Player id not found.");
				return (byte) RegisterLoginResponse.UNSUCCESSFUL;
			}
		} catch (GameDatabaseException e) {
			LOGGER.catching(e);
			return (byte) RegisterLoginResponse.UNSUCCESSFUL;
		}

		if (!isSimRegister) {
			System.out.println("Register was successful!");
		}
		return (byte) RegisterLoginResponse.REGISTER_SUCCESSFUL;
	}

	private String rot13(String word) {
		StringBuilder sb = new StringBuilder();
		for (int i = 0; i < word.length(); i++) {
			char c = word.charAt(i);
			if       (c >= 'a' && c <= 'm') c += 13;
			else if  (c >= 'A' && c <= 'M') c += 13;
			else if  (c >= 'n' && c <= 'z') c -= 13;
			else if  (c >= 'N' && c <= 'Z') c -= 13;
			sb.append(c);
		}
		return sb.toString();
	}

	private boolean isDisallowedUsername(String username) {
		final String [] disallowed = { "nff", "shpx", "fuvg", "fhpx", "cravf", "intvan", "chffl", "juber", "fyhg", "qvpx", "pbpx", "phag", "zbq", "z0q", "zbqrengbe", "nqzva", "nqz1a", "nqzvavfgengbe", "nszna", "wntrk", "wnin", "cuhpx", "shx", "shk", "shd", "snd", "sbp", "sbx", "sbbx", "srx", "snpx", "sbrx", "srpx", "sphx", "shxp", "spx", "shvpx", "suhpx", "cuhx", "puvg", "fpuvg", "fuwg", "fung", "furg",
			"fvug", "fugv", "fug", "penc", "ovgpu", "owgpu", "13vgpu", "ovpu", "ovngpu", "ovbgpu", "onfgneq", "fcnfgvp", "ergneq", "avtn", "avte", "avtre", "puvax", "jbc", "pbba", "uvgyre", "anmv", "dhrre", "xjrre", "snt", "sntbg", "yrfob", "oryyraq", "travgny", "qvx", "jnat", "fuybat", "pbx", "cevp", "jvyyl", "obare", "rerpgvba", "onyf", "obyybpx", "grfgvpyr", "fpebghz", "ahgf", "pyvg", "fyvg", "inqtr",
			"snaal", "gjng", "chfl", "chfv", "chff", "oernfg", "gvg", "gvgf", "obbo", "avcyr", "nefr", "genal", "genavr", "genaavr", "genaal", "cbb", "cbbc", "nahf", "erpghz", "nany", "ohgg", "nffubyr", "nffu01r", "hevangr", "cvff", "hevar", "gheq", "snrprf", "rkperzrag", "rkpergr", "sneg", "pnpx", "fcrez", "phz", "fchax", "fzrt", "frzra", "rwnphyng", "encr", "encvfg", "fgnyx", "jnax", "znfgheongr",
			"znfgreongvat", "cvzc", "cebfgvghg", "crqbcuvyr", "cnrqbcuvyr", "fyncre", "fynt", "yvpx", "oybwbo", "sryng", "phavyvat", "anxrq", "haqerff", "ahqr", "pbaqbz", "qvyqb", "ivoengbe", "obaqntr", "fcnax", "ubeal", "guebo", "gnzcba", "oybbqent", "cnagl", "cbea", "cnfjbeq", "cnff", "cjbeq", "fvrnt urvy", "fvrt urvy", "snx", "snpxvat", "fperj", "avttn", "zbqehar", "nubyr", "arteb", "ywpx", "qwpx",
			"gwgf", "ubeavr", "zhfgreongr", "avtt" };

		final String[] staff = { "mod", "moderator", "mordorator", "admin", "administrator",
			"afman", "owner", "jagex", "java" };

		final String global = "global";

		boolean notAllowed = false;
		String user = username.toLowerCase();
		user = user.replaceAll("1", "i");
		user = user.replaceAll("0", "o");

		for (String word : disallowed) {
			if (user.contains(rot13(word))) {
				notAllowed = true;
				break;
			}
		}

		// check for staff related
		if (!notAllowed && !getServer().getConfig().CHAR_NAME_CAN_CONTAIN_MOD) {
			int indexWord;
			int indexChk;
			char charChk, tmpChar;
			for (String word : staff) {
				indexWord = user.indexOf(word);
				if (indexWord != -1) {
					// possible, further check
					// is a reserved word, disallow
					if (user.equals(word)) {
						notAllowed = true;
						break;
					} else if (user.length() <= word.length()) {
						continue;
					}

					if (indexWord == 0) {
						// check if starts with followed by space, underscore or dash
						// since players could have names like Modesto
						indexChk = indexWord + word.length();
						charChk = user.charAt(indexChk);
						if (charChk == ' ' || charChk == '_' || charChk == '-') {
							notAllowed = true;
							break;
						}
						continue;
					}

					// check if before and after is space, underscore or dash
					// since players could have names like Willmod
					indexChk = indexWord - 1;
					charChk = user.charAt(indexChk);
					tmpChar = user.length() == indexWord + word.length() ? ' ' : user.charAt(indexWord + word.length());
					if ((charChk == ' ' || charChk == '_' || charChk == '-')
						&& (tmpChar == ' ' || tmpChar == '_' || tmpChar == '-')) {
						notAllowed = true;
						break;
					}
				}
			}
		}

		if (!notAllowed && !getServer().getConfig().CHAR_NAME_CAN_EQUAL_GLOBAL) {
			// pre 2009 clients cant have friends with special characters, in those cases
			// Global$ will be Global for them
			if (user.equals(global)) {
				notAllowed = true;
			}
		}

		return notAllowed;
	}
}
