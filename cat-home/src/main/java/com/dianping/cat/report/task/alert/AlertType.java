package com.dianping.cat.report.task.alert;

public enum AlertType {

	Business("business"),

	Network("network"),

	DataBase("database"),

	System("system"),

	Exception("exception"),

	HeartBeat("heartbeat"),

	ThirdParty("thirdParty"),

	FrontEndException("frontEnd"),

	App("app"),

	Web("web"),

	Transaction("transaction");

	private String m_name;

	private AlertType(String name) {
		m_name = name;
	}

	public String getName() {
		return m_name;
	}

	public static AlertType getTypeByName(String name) {
		for (AlertType type : AlertType.values()) {
			if (type.getName().equals(name)) {
				return type;
			}
		}
		return null;
	}

}
