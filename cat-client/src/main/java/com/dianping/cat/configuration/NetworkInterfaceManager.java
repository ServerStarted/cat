package com.dianping.cat.configuration;

import java.net.Inet4Address;
import java.net.InetAddress;
import java.net.NetworkInterface;
import java.net.SocketException;
import java.net.UnknownHostException;
import java.util.Collections;
import java.util.List;

public enum NetworkInterfaceManager {
	INSTANCE;

	private InetAddress m_local;

	private NetworkInterfaceManager() {
		load();
	}

	public String getLocalHostAddress() {
		return m_local.getHostAddress();
	}

	public String getLocalHostName() {
		try {
			return InetAddress.getLocalHost().getHostName();
		} catch (UnknownHostException e) {
			return m_local.getHostName();
		}
	}

	private void load() {
		String ip = System.getProperty("host.ip");

		if (ip != null) {
			try {
				m_local = InetAddress.getByName(ip);
				return;
			} catch (Exception e) {
				System.err.println(e);
				// ignore
			}
		}

		try {
			List<NetworkInterface> nis = Collections.list(NetworkInterface.getNetworkInterfaces());
			InetAddress local = null;

			for (NetworkInterface ni : nis) {
				try {
					if (ni.isUp()) {
						List<InetAddress> addresses = Collections.list(ni.getInetAddresses());

						for (InetAddress address : addresses) {
							if (address instanceof Inet4Address) {
								if (address.isLoopbackAddress() || address.isSiteLocalAddress()) {
									if (local == null) {
										local = address;
									} else if (local.isLoopbackAddress() && address.isSiteLocalAddress()) {
										// site local address has higher priority than
										// loopback address
										local = address;
									} else if (local.isSiteLocalAddress() && address.isSiteLocalAddress()) {
										// site local address with a host name has higher
										// priority than one without host name
										if (local.getHostName().equals(local.getHostAddress())
										      && !address.getHostName().equals(address.getHostAddress())) {
											local = address;
										}
									}
								} else {
									local = address;
								}
							}
						}
					}
				} catch (Exception e) {
					// ignore
				}
			}

			m_local = local;
		} catch (SocketException e) {
			// ignore it
		}
	}
}