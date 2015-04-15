package com.dianping.cat.agent.monitor;

import java.util.Arrays;
import java.util.List;

public class CatServers {

	private String SYSTEM_URL = "http://%1$s/cat/r/monitor?op=batch";

	private List<String> CAT_SERVERS = Arrays.asList("10.1.110.57:8080", "10.1.110.23:8080", "10.1.110.21:8080");

	public String buildSystemUrl(String server) {
		return String.format(SYSTEM_URL, server);
	}

	public List<String> getServers() {
		return CAT_SERVERS;
	}
}
